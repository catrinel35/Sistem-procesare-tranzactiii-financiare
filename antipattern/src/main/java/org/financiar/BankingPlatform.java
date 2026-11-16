package src.main.java.org.financiar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class BankingPlatform {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE = new BigDecimal("1.00");
    private static final Duration CANCELLATION_WINDOW = Duration.ofHours(24);
    private static final Duration FRAUD_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Transaction> transactions = new HashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();

    public BankingPlatform() {
        this(Clock.systemUTC());
    }

    public BankingPlatform(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized AccountView openAccount(
            String id, String owner, String type, String currency, BigDecimal openingBalance) {
        String accountId = requireText(id, "account ID");
        AccountType accountType = AccountType.valueOf(normalize(type, "account type"));
        Currency accountCurrency = Currency.valueOf(normalize(currency, "currency"));
        BigDecimal balance = money(openingBalance);
        if (balance.signum() < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative.");
        }
        if (accounts.containsKey(accountId)) {
            throw new IllegalArgumentException("Account ID already exists: " + accountId);
        }

        Account account = new Account(accountId, owner == null ? "" : owner.trim(),
                accountType, accountCurrency, balance);
        accounts.put(accountId, account);
        return view(account);
    }

    public synchronized Receipt process(TransactionRequest input) {
        Objects.requireNonNull(input, "request");
        String requestId = requireText(input.requestId(), "request ID");
        Transaction previous = transactions.get(requestId);
        if (previous != null) {
            return previous.receipt;
        }

        Instant now = clock.instant();
        String typeName = input.type() == null ? "" : input.type().trim().toUpperCase(Locale.ROOT);
        TransactionType type = parseType(typeName);
        TransactionRequest request = new TransactionRequest(
                requestId,
                type == null ? typeName : type.name(),
                normalizeId(input.fromAccountId()),
                normalizeId(input.toAccountId()),
                input.currency() == null ? null : input.currency().trim().toUpperCase(Locale.ROOT),
                input.amount());

        if (type == null) {
            return reject(request, null, now, "UNKNOWN_TYPE");
        }

        BigDecimal amount;
        try {
            amount = money(request.amount());
        } catch (NullPointerException ex) {
            return reject(request, type, now, "INVALID_AMOUNT");
        }
        if (amount.signum() <= 0) {
            return reject(request, type, now, "INVALID_AMOUNT");
        }
        request = new TransactionRequest(request.requestId(), request.type(),
                request.fromAccountId(), request.toAccountId(), request.currency(), amount);

        if ((type == TransactionType.TRANSFER || type == TransactionType.EXCHANGE)
                && request.fromAccountId() != null
                && request.fromAccountId().equals(request.toAccountId())) {
            return reject(request, type, now, "SAME_ACCOUNT");
        }

        Account from = request.fromAccountId() == null ? null : accounts.get(request.fromAccountId());
        Account to = request.toAccountId() == null ? null : accounts.get(request.toAccountId());
        if (requiresFrom(type) && from == null || requiresTo(type) && to == null) {
            return reject(request, type, now, "ACCOUNT_NOT_FOUND");
        }

        if (!currencyMatches(request.currency(), from, to, type)) {
            return reject(request, type, now, "CURRENCY_MISMATCH");
        }
        if (type == TransactionType.EXCHANGE && from.currency == to.currency) {
            return reject(request, type, now, "SAME_CURRENCY");
        }

        if (isOutgoing(type)) {
            String limitFailure = checkDailyLimit(from, amount, now);
            if (limitFailure != null) {
                return reject(request, type, now, limitFailure);
            }
            if (recentOutgoingCount(from.id, now) >= 5) {
                return reject(request, type, now, "FRAUD_SUSPECTED");
            }
        }

        BigDecimal fee = fee(type, from, amount);
        BigDecimal credited = amount;
        if (type == TransactionType.EXCHANGE) {
            credited = exchangeAmount(amount, from.currency, to.currency);
        }
        if (from != null && from.balance.compareTo(amount.add(fee)) < 0) {
            return reject(request, type, now, "INSUFFICIENT_FUNDS");
        }

        if (type == TransactionType.DEPOSIT) {
            to.balance = to.balance.add(amount);
        } else if (type == TransactionType.WITHDRAWAL) {
            from.balance = from.balance.subtract(amount).subtract(fee);
        } else {
            from.balance = from.balance.subtract(amount).subtract(fee);
            to.balance = to.balance.add(credited);
        }

        Receipt receipt = new Receipt(requestId, TransactionStatus.COMPLETED, null,
                now, fee, credited);
        transactions.put(requestId, new Transaction(request, type, now, fee, credited, receipt));
        addHistory(request, type, now, TransactionStatus.COMPLETED.name(), type.name());
        return receipt;
    }

    public synchronized Receipt cancel(String requestIdInput) {
        String requestId = requireText(requestIdInput, "request ID");
        Transaction transaction = transactions.get(requestId);
        Instant now = clock.instant();
        if (transaction == null) {
            return new Receipt(requestId, TransactionStatus.REJECTED, "UNKNOWN_REQUEST",
                    now, ZERO, ZERO);
        }
        if (transaction.status != TransactionStatus.COMPLETED) {
            return rejectCancellation(transaction, now, "NOT_CANCELLABLE");
        }
        if (Duration.between(transaction.completedAt, now).compareTo(CANCELLATION_WINDOW) > 0) {
            return rejectCancellation(transaction, now, "CANCEL_WINDOW_EXPIRED");
        }

        Account from = account(transaction.request.fromAccountId());
        Account to = account(transaction.request.toAccountId());
        BigDecimal amount = transaction.request.amount();
        if ((transaction.type == TransactionType.DEPOSIT
                && to.balance.compareTo(amount) < 0)
                || ((transaction.type == TransactionType.TRANSFER
                || transaction.type == TransactionType.EXCHANGE)
                && to.balance.compareTo(transaction.creditedAmount) < 0)) {
            return rejectCancellation(transaction, now, "NOT_CANCELLABLE");
        }

        if (transaction.type == TransactionType.DEPOSIT) {
            to.balance = to.balance.subtract(amount);
        } else if (transaction.type == TransactionType.WITHDRAWAL) {
            from.balance = from.balance.add(amount).add(transaction.fee);
        } else {
            to.balance = to.balance.subtract(transaction.creditedAmount);
            from.balance = from.balance.add(amount).add(transaction.fee);
        }

        transaction.status = TransactionStatus.CANCELLED;
        addHistory(transaction.request, transaction.type, now,
                TransactionStatus.CANCELLED.name(), "CANCELLED");
        return new Receipt(requestId, TransactionStatus.CANCELLED, null,
                now, ZERO, ZERO);
    }

    public synchronized Optional<AccountView> getAccount(String id) {
        Account account = id == null ? null : accounts.get(id.trim());
        return account == null ? Optional.empty() : Optional.of(view(account));
    }

    public synchronized List<HistoryEntry> getHistory(String accountId) {
        if (accountId == null || !accounts.containsKey(accountId.trim())) {
            return List.of();
        }
        String id = accountId.trim();
        return history.stream().filter(entry -> entry.accountId().equals(id)).toList();
    }

    private Receipt reject(
            TransactionRequest request, TransactionType type, Instant now, String reason) {
        Receipt receipt = new Receipt(request.requestId(), TransactionStatus.REJECTED,
                reason, now, ZERO, ZERO);
        transactions.put(request.requestId(),
                new Transaction(request, type, now, ZERO, ZERO, receipt));
        addHistory(request, type, now, TransactionStatus.REJECTED.name(), reason);
        return receipt;
    }

    private Receipt rejectCancellation(Transaction transaction, Instant now, String reason) {
        addHistory(transaction.request, transaction.type, now,
                TransactionStatus.REJECTED.name(), reason);
        return new Receipt(transaction.request.requestId(), TransactionStatus.REJECTED,
                reason, now, ZERO, ZERO);
    }

    private void addHistory(
            TransactionRequest request, TransactionType type, Instant time,
            String event, String detail) {
        for (String id : participantIds(request, type)) {
            if (accounts.containsKey(id)) {
                history.add(new HistoryEntry(time, request.requestId(), id, event, detail));
            }
        }
    }

    private List<String> participantIds(TransactionRequest request, TransactionType type) {
        List<String> ids = new ArrayList<>();
        if (type == TransactionType.DEPOSIT) {
            addIfPresent(ids, request.toAccountId());
        } else if (type == TransactionType.WITHDRAWAL) {
            addIfPresent(ids, request.fromAccountId());
        } else if (type == TransactionType.TRANSFER || type == TransactionType.EXCHANGE) {
            addIfPresent(ids, request.fromAccountId());
            addIfPresent(ids, request.toAccountId());
        } else {
            addIfPresent(ids, request.fromAccountId());
            addIfPresent(ids, request.toAccountId());
        }
        return ids;
    }

    private static void addIfPresent(List<String> ids, String id) {
        if (id != null && !ids.contains(id)) {
            ids.add(id);
        }
    }

    private String checkDailyLimit(Account from, BigDecimal amount, Instant now) {
        BigDecimal limit = from.type == AccountType.SAVINGS
                ? new BigDecimal("5000.00") : new BigDecimal("10000.00");
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        BigDecimal used = transactions.values().stream()
                .filter(tx -> tx.status == TransactionStatus.COMPLETED
                        && isOutgoing(tx.type)
                        && tx.request.fromAccountId().equals(from.id)
                        && tx.completedAt.atZone(ZoneOffset.UTC).toLocalDate().equals(today))
                .map(tx -> tx.request.amount())
                .reduce(ZERO, BigDecimal::add);
        return used.add(amount).compareTo(limit) > 0 ? "DAILY_LIMIT_EXCEEDED" : null;
    }

    private long recentOutgoingCount(String accountId, Instant now) {
        Instant cutoff = now.minus(FRAUD_WINDOW);
        return transactions.values().stream()
                .filter(tx -> tx.status == TransactionStatus.COMPLETED
                        && isOutgoing(tx.type)
                        && tx.request.fromAccountId().equals(accountId)
                        && !tx.completedAt.isBefore(cutoff)
                        && !tx.completedAt.isAfter(now))
                .count();
    }

    private static boolean currencyMatches(
            String requested, Account from, Account to, TransactionType type) {
        if (requested == null) {
            return false;
        }
        return switch (type) {
            case DEPOSIT -> requested.equals(fromCurrency(to));
            case WITHDRAWAL -> requested.equals(fromCurrency(from));
            case TRANSFER -> requested.equals(fromCurrency(from))
                    && requested.equals(fromCurrency(to));
            case EXCHANGE -> requested.equals(fromCurrency(from));
        };
    }

    private static String fromCurrency(Account account) {
        return account == null ? null : account.currency.name();
    }

    private static BigDecimal fee(TransactionType type, Account from, BigDecimal amount) {
        return switch (type) {
            case DEPOSIT -> ZERO;
            case WITHDRAWAL -> amount.compareTo(new BigDecimal("100.00")) < 0
                    ? new BigDecimal("2.00") : ZERO;
            case TRANSFER -> transferFee(from.type, amount);
            case EXCHANGE -> percentageFee(amount, new BigDecimal("0.01"));
        };
    }

    private static BigDecimal transferFee(AccountType type, BigDecimal amount) {
        return switch (type) {
            case CURRENT -> percentageFee(amount, new BigDecimal("0.005"));
            case BUSINESS -> percentageFee(amount, new BigDecimal("0.002"));
            case SAVINGS -> ZERO;
        };
    }

    private static BigDecimal percentageFee(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP).max(ONE);
    }

    private static BigDecimal exchangeAmount(
            BigDecimal amount, Currency source, Currency destination) {
        return amount.multiply(rate(source)).divide(rate(destination), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(Currency currency) {
        return switch (currency) {
            case RON -> new BigDecimal("1.0000");
            case EUR -> new BigDecimal("5.0000");
            case USD -> new BigDecimal("4.6000");
        };
    }

    private Account account(String id) {
        return id == null ? null : accounts.get(id);
    }

    private static AccountView view(Account account) {
        return new AccountView(account.id, account.owner, account.type, account.currency, account.balance);
    }

    private static BigDecimal money(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalize(String value, String field) {
        return requireText(value, field).toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be empty.");
        }
        return value.trim();
    }

    private static String normalizeId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static TransactionType parseType(String value) {
        try {
            return TransactionType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean requiresFrom(TransactionType type) {
        return type == TransactionType.WITHDRAWAL
                || type == TransactionType.TRANSFER
                || type == TransactionType.EXCHANGE;
    }

    private static boolean requiresTo(TransactionType type) {
        return type == TransactionType.DEPOSIT
                || type == TransactionType.TRANSFER
                || type == TransactionType.EXCHANGE;
    }

    private static boolean isOutgoing(TransactionType type) {
        return type == TransactionType.WITHDRAWAL
                || type == TransactionType.TRANSFER
                || type == TransactionType.EXCHANGE;
    }

    public enum AccountType {
        CURRENT, SAVINGS, BUSINESS
    }

    public enum Currency {
        RON, EUR, USD
    }

    public enum TransactionStatus {
        COMPLETED, REJECTED, CANCELLED
    }

    public record AccountView(
            String id, String owner, AccountType type, Currency currency, BigDecimal balance) {
    }

    public record TransactionRequest(
            String requestId,
            String type,
            String fromAccountId,
            String toAccountId,
            String currency,
            BigDecimal amount) {
    }

    public record Receipt(
            String requestId,
            TransactionStatus status,
            String code,
            Instant time,
            BigDecimal fee,
            BigDecimal creditedAmount) {
    }

    public record HistoryEntry(
            Instant time, String requestId, String accountId, String event, String detail) {
    }

    private enum TransactionType {
        DEPOSIT, WITHDRAWAL, TRANSFER, EXCHANGE
    }

    private static final class Account {
        private final String id;
        private final String owner;
        private final AccountType type;
        private final Currency currency;
        private BigDecimal balance;

        private Account(
                String id, String owner, AccountType type, Currency currency, BigDecimal balance) {
            this.id = id;
            this.owner = owner;
            this.type = type;
            this.currency = currency;
            this.balance = balance;
        }
    }

    private static final class Transaction {
        private final TransactionRequest request;
        private final TransactionType type;
        private final Instant completedAt;
        private final BigDecimal fee;
        private final BigDecimal creditedAmount;
        private final Receipt receipt;
        private TransactionStatus status;

        private Transaction(
                TransactionRequest request,
                TransactionType type,
                Instant completedAt,
                BigDecimal fee,
                BigDecimal creditedAmount,
                Receipt receipt) {
            this.request = request;
            this.type = type;
            this.completedAt = completedAt;
            this.fee = fee;
            this.creditedAmount = creditedAmount;
            this.receipt = receipt;
            this.status = receipt.status();
        }
    }
}
