package src.main.java.org.financiar;

import java.math.BigDecimal;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class Main {
    private static final Scanner INPUT = new Scanner(System.in);
    private static final BankingPlatform BANK = new BankingPlatform();

    public static void main(String[] args) {
        boolean running = true;
        while (running) {
            System.out.println("""
                    \nBanking platform
                    1. Create account
                    2. Deposit
                    3. Withdraw
                    4. Transfer
                    5. Currency exchange
                    6. Account inquiry
                    7. Transaction history
                    8. Cancel transaction
                    0. Exit""");

            switch (readLine("Choose an option: ")) {
                case "1" -> createAccount();
                case "2" -> process("DEPOSIT");
                case "3" -> process("WITHDRAWAL");
                case "4" -> process("TRANSFER");
                case "5" -> process("EXCHANGE");
                case "6" -> inquire();
                case "7" -> showHistory();
                case "8" -> cancel();
                case "0" -> running = false;
                default -> System.out.println("Unknown menu option.");
            }
        }
        System.out.println("Goodbye.");
    }

    private static void createAccount() {
        String id = readLine("Account ID: ");
        String owner = readLine("Owner: ");
        String type = readLine("Account type (CURRENT, SAVINGS, BUSINESS): ");
        String currency = readLine("Currency (RON, EUR, USD): ");
        BigDecimal balance = readAmount("Opening balance: ");

        try {
            System.out.println("Created: " + BANK.openAccount(id, owner, type, currency, balance));
        } catch (IllegalArgumentException ex) {
            System.out.println("Could not create account: " + ex.getMessage());
        }
    }

    private static void process(String type) {
        String from = null;
        String to = null;
        switch (type) {
            case "DEPOSIT" -> to = readLine("Destination account ID: ");
            case "WITHDRAWAL" -> from = readLine("Source account ID: ");
            default -> {
                from = readLine("Source account ID: ");
                to = readLine("Destination account ID: ");
            }
        }

        String currency = readLine("Currency (RON, EUR, USD): ");
        BigDecimal amount = readAmount("Amount: ");
        BankingPlatform.TransactionRequest request =
                new BankingPlatform.TransactionRequest(
                        UUID.randomUUID().toString(), type, from, to, currency, amount);
        System.out.println(BANK.process(request));
    }

    private static void inquire() {
        String id = readLine("Account ID: ");
        BANK.getAccount(id).ifPresentOrElse(
                account -> System.out.println(account),
                () -> System.out.println("Account not found."));
    }

    private static void showHistory() {
        String id = readLine("Account ID: ");
        List<BankingPlatform.HistoryEntry> history = BANK.getHistory(id);
        if (history.isEmpty()) {
            System.out.println("No transaction history.");
            return;
        }
        history.forEach(System.out::println);
    }

    private static void cancel() {
        String requestId = readLine("Transaction request ID: ");
        System.out.println(BANK.cancel(requestId));
    }

    private static String readLine(String prompt) {
        System.out.print(prompt);
        return INPUT.nextLine().trim();
    }

    private static BigDecimal readAmount(String prompt) {
        while (true) {
            String value = readLine(prompt);
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException ex) {
                System.out.println("Enter a valid decimal amount.");
            }
        }
    }
}
