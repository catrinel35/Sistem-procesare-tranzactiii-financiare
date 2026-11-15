package org.financiar;

import org.financiar.model.*;
import org.financiar.model.enums.AccountType;
import org.financiar.model.enums.RejectionReason;
import org.financiar.model.enums.Status;
import org.financiar.model.enums.TransactionType;
import org.financiar.model.enums.Currency;
import org.financiar.repository.AccountRepository;
import org.financiar.repository.InMemoryAccountRepository;
import org.financiar.model.HistoryEntry;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public class PatternsTransactionService {

    private static final Duration CANCEL_WINDOW = Duration.ofHours(24);
    private static final Duration FRAUD_WINDOW = Duration.ofHours(1);
    private static final int FRAUD_MAX_RECENT = 5;

    private final Clock clock;
    private final AccountRepository accounts;
    private final HistoryLog history = new HistoryLog();
    private final Map<String, Receipt> receipts = new HashMap<>();
    private final Map<String, TransactionRecord> transactions = new HashMap<>();

    public PatternsTransactionService() {
        this(Clock.systemUTC());
    }

    public PatternsTransactionService(Clock clock) {
        this(clock, new InMemoryAccountRepository());
    }

    public PatternsTransactionService(Clock clock, AccountRepository accounts) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    public AccountView openAccount(OpenAccountRequest request) {
        Currency currency = Currency.parse(request.currency())
                .orElseThrow(() -> new IllegalArgumentException("Monedă necunoscută: " + request.currency()));
        AccountType type = AccountType.parse(request.type())
                .orElseThrow(() -> new IllegalArgumentException("Tip de cont necunoscut: " + request.type()));
        if (accounts.exists(request.accountId())) {
            throw new IllegalArgumentException("Contul există deja: " + request.accountId());
        }
        Account account = new Account(request.accountId(), type, currency,
                new Money(request.initialBalance(), currency));
        accounts.add(account);
        return view(account);
    }

    public Optional<AccountView> getAccount(String accountId) {
        return accounts.find(accountId).map(PatternsTransactionService::view);
    }

    public List<HistoryEntry> getHistory(String accountId) {
        return history.forAccount(accountId);
    }

    // ------------------------------------------------------------------ procesare

    public Receipt process(TransactionRequest request) {
        Receipt previous = receipts.get(request.requestId());
        if (previous != null) {
            return previous;
        }
        Account from = find(request.from());
        Account to = find(request.to());
        Instant now = clock.instant();

        Optional<TransactionType> parsed = TransactionType.parse(request.type());
        if (parsed.isEmpty()) {
            return reject(request, RejectionReason.UNKNOWN_TYPE, from, to, now);
        }
        TransactionType type = parsed.get();
        if (request.amount() == null || request.amount().signum() <= 0) {
            return reject(request, RejectionReason.INVALID_AMOUNT, from, to, now);
        }
        Optional<RejectionReason> invalid = checkAccounts(type, request, from, to);
        if (invalid.isPresent()) {
            return reject(request, invalid.get(), from, to, now);
        }

        Account source = type == TransactionType.DEPOSIT ? to : from;
        Money amount = new Money(request.amount(), source.currency());

        if (type.isOutgoing()) {
            Optional<RejectionReason> blocked = checkLimitAndFraud(from, amount, now);
            if (blocked.isPresent()) {
                return reject(request, blocked.get(), from, to, now);
            }
        }

        Money fee = feeFor(type, from, amount);
        Money total = amount.plus(fee);
        if (type.isOutgoing() && !from.canCover(total)) {
            return reject(request, RejectionReason.INSUFFICIENT_FUNDS, from, to, now);
        }

        Money received = amount;
        switch (type) {
            case DEPOSIT -> to.credit(amount);
            case WITHDRAWAL -> from.debit(total);
            case TRANSFER -> {
                from.debit(total);
                to.credit(amount);
            }
            case EXCHANGE -> {
                received = amount.convertTo(to.currency());
                from.debit(total);
                to.credit(received);
            }
        }

        TransactionRecord record = new TransactionRecord(request.requestId(), type,
                from == null ? null : from.id(), to == null ? null : to.id(), amount, fee, received, now);
        transactions.put(request.requestId(), record);
        if (type.isOutgoing()) {
            from.recordOutgoing(record);
        }
        Receipt receipt = new Receipt(request.requestId(), Status.COMPLETED.name(), request.amount(),
                fee.amount(), null, now);
        receipts.put(request.requestId(), receipt);
        logHistory(from, to, request.requestId(), Status.COMPLETED, null, now);
        return receipt;
    }

    private Optional<RejectionReason> checkAccounts(TransactionType type, TransactionRequest request,
                                                    Account from, Account to) {
        boolean needsBoth = type == TransactionType.TRANSFER || type == TransactionType.EXCHANGE;
        if (needsBoth && request.from() != null && request.from().equals(request.to())) {
            return Optional.of(RejectionReason.SAME_ACCOUNT);
        }
        boolean missing = switch (type) {
            case DEPOSIT -> to == null;
            case WITHDRAWAL -> from == null;
            case TRANSFER, EXCHANGE -> from == null || to == null;
        };
        if (missing) {
            return Optional.of(RejectionReason.ACCOUNT_NOT_FOUND);
        }
        String code = request.currency();
        boolean mismatch = switch (type) {
            case DEPOSIT -> !to.currency().name().equals(code);
            case WITHDRAWAL, EXCHANGE -> !from.currency().name().equals(code);
            case TRANSFER -> !from.currency().name().equals(code) || !to.currency().name().equals(code);
        };
        if (mismatch) {
            return Optional.of(RejectionReason.CURRENCY_MISMATCH);
        }
        if (type == TransactionType.EXCHANGE && from.currency() == to.currency()) {
            return Optional.of(RejectionReason.SAME_CURRENCY);
        }
        return Optional.empty();
    }

    private Optional<RejectionReason> checkLimitAndFraud(Account from, Money amount, Instant now) {
        BigDecimal usedToday = BigDecimal.ZERO;
        int recent = 0;
        for (TransactionRecord record : from.outgoing()) {
            if (!record.isCompleted()) {
                continue;
            }
            if (sameUtcDay(record.time(), now)) {
                usedToday = usedToday.add(record.amount().amount());
            }
            if (record.time().isAfter(now.minus(FRAUD_WINDOW))) {
                recent++;
            }
        }
        if (usedToday.add(amount.amount()).compareTo(from.type().dailyLimit()) > 0) {
            return Optional.of(RejectionReason.DAILY_LIMIT_EXCEEDED);
        }
        if (recent >= FRAUD_MAX_RECENT) {
            return Optional.of(RejectionReason.FRAUD_SUSPECTED);
        }
        return Optional.empty();
    }

    private Money feeFor(TransactionType type, Account from, Money amount) {
        Currency currency = amount.currency();
        return switch (type) {
            case DEPOSIT -> Money.zero(currency);
            case WITHDRAWAL -> amount.amount().compareTo(new BigDecimal("100")) < 0
                    ? new Money(new BigDecimal("2.00"), currency)
                    : Money.zero(currency);
            case TRANSFER -> switch (from.type()) {
                case CURRENT -> percentWithMinimum(amount, "0.005");
                case BUSINESS -> percentWithMinimum(amount, "0.002");
                case SAVINGS -> Money.zero(currency);
            };
            case EXCHANGE -> percentWithMinimum(amount, "0.01");
        };
    }

    private static Money percentWithMinimum(Money amount, String rate) {
        BigDecimal raw = amount.amount().multiply(new BigDecimal(rate));
        BigDecimal minimum = new BigDecimal("1.00");
        return new Money(raw.compareTo(minimum) < 0 ? minimum : raw, amount.currency());
    }

    public Receipt cancel(String requestId, String reason) {
        Instant now = clock.instant();
        TransactionRecord record = transactions.get(requestId);
        if (record == null) {
            return new Receipt(requestId, Status.REJECTED.name(), BigDecimal.ZERO, BigDecimal.ZERO,
                    RejectionReason.UNKNOWN_REQUEST.name(), now);
        }
        if (!record.isCompleted()) {
            return rejectedCancel(record, RejectionReason.NOT_CANCELLABLE, now);
        }
        if (now.isAfter(record.time().plus(CANCEL_WINDOW))) {
            return rejectedCancel(record, RejectionReason.CANCEL_WINDOW_EXPIRED, now);
        }
        Account from = find(record.fromId());
        Account to = find(record.toId());
        Money refund = record.amount().plus(record.fee());
        switch (record.type()) {
            case DEPOSIT -> {
                if (!to.canCover(record.received())) {
                    return rejectedCancel(record, RejectionReason.NOT_CANCELLABLE, now);
                }
                to.debit(record.received());
            }
            case WITHDRAWAL -> from.credit(refund);
            case TRANSFER, EXCHANGE -> {
                if (!to.canCover(record.received())) {
                    return rejectedCancel(record, RejectionReason.NOT_CANCELLABLE, now);
                }
                to.debit(record.received());
                from.credit(refund);
            }
        }
        record.markCancelled();
        Receipt receipt = new Receipt(requestId, Status.CANCELLED.name(), record.amount().amount(),
                record.fee().amount(), null, now);
        receipts.put(requestId, receipt);
        logHistory(from, to, requestId, Status.CANCELLED, reason, now);
        return receipt;
    }

    private Receipt rejectedCancel(TransactionRecord record, RejectionReason reason, Instant now) {
        return new Receipt(record.requestId(), Status.REJECTED.name(), record.amount().amount(),
                record.fee().amount(), reason.name(), now);
    }

    private Receipt reject(TransactionRequest request, RejectionReason reason, Account from, Account to,
                           Instant now) {
        Receipt receipt = new Receipt(request.requestId(), Status.REJECTED.name(), request.amount(),
                BigDecimal.ZERO, reason.name(), now);
        receipts.put(request.requestId(), receipt);
        logHistory(from, to, request.requestId(), Status.REJECTED, reason.name(), now);
        return receipt;
    }

    private void logHistory(Account from, Account to, String requestId, Status status, String detail, Instant now) {
        if (from != null) {
            history.record(from.id(), new HistoryEntry(now, requestId, from.id(), status.name(), detail));
        }
        if (to != null && to != from) {
            history.record(to.id(), new HistoryEntry(now, requestId, to.id(), status.name(), detail));
        }
    }

    private Account find(String accountId) {
        return accountId == null ? null : accounts.find(accountId).orElse(null);
    }

    private static boolean sameUtcDay(Instant first, Instant second) {
        return first.atOffset(ZoneOffset.UTC).toLocalDate().equals(second.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    private static AccountView view(Account account) {
        return new AccountView(account.id(), account.type().name(), account.currency().name(),
                account.balance().amount());
    }
}
