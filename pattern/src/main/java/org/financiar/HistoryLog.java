package org.financiar;

import org.financiar.model.HistoryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HistoryLog {
    private final Map<String, List<HistoryEntry>> byAccount = new HashMap<>();

    public void record(String accountId, HistoryEntry entry) {
        byAccount.computeIfAbsent(accountId, id -> new ArrayList<>()).add(entry);
    }

    public List<HistoryEntry> forAccount(String accountId) {
        return new ArrayList<>(byAccount.getOrDefault(accountId, List.of()));
    }
}
