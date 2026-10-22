package com.assessment.transactions.model;
import java.util.EnumMap;
import java.util.Map;
public class ProcessingSummary {
    private final Map<TransactionStatus, Long> counts = new EnumMap<TransactionStatus, Long>(TransactionStatus.class);
    private final int total;
    public ProcessingSummary(Iterable<TransactionRecord> records) {
        int n=0;
        for (TransactionRecord r: records) { n++; Long old=counts.get(r.getStatus()); counts.put(r.getStatus(), old==null?1L:old+1L); }
        total=n;
        for (TransactionStatus s: TransactionStatus.values()) if(!counts.containsKey(s)) counts.put(s,0L);
    }
    public int getTotal(){return total;} public Map<TransactionStatus,Long> getCountsByStatus(){return counts;}
}
