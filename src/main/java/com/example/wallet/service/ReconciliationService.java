package com.example.wallet.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {

  private final JdbcTemplate jdbc; private final TransactionTemplate tx;

  public ReconciliationService(JdbcTemplate jdbc,TransactionTemplate tx) {
    this.jdbc=jdbc;this.tx=tx;
  }

  public void run() {

    tx.execute(s -> {
      s.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

      try {
        jdbc.queryForList("SELECT job_name FROM job_locks WHERE job_name='nightly-reconciliation' FOR UPDATE NOWAIT");
      } catch (DataAccessException busy) {
        return -1L;
      }

      Long id = jdbc.queryForObject("INSERT INTO reconciliation_runs(status) VALUES ('RUNNING') RETURNING id", Long.class);

      int issues = 0;

      var sums = jdbc.queryForMap("SELECT COALESCE(SUM(amount_paise) FILTER(WHERE direction='DEBIT'),0) d,COALESCE(SUM(amount_paise) FILTER(WHERE direction='CREDIT'),0) c FROM ledger_entries");
      long d = ((Number) sums.get("d")).longValue(), c = ((Number) sums.get("c")).longValue();
      if (d != c) {
        issue(id, "GLOBAL_LEDGER_BALANCE", null, Long.toString(d), Long.toString(c));
        issues++;
      }
      issues += jdbc.update("INSERT INTO reconciliation_issues(run_id,check_code,entity_id,expected_value,actual_value) SELECT ?,'TRANSFER_UNBALANCED',t.id,'debits=credits',COALESCE(SUM(le.amount_paise) FILTER(WHERE le.direction='DEBIT'),0)::text||'='||COALESCE(SUM(le.amount_paise) FILTER(WHERE le.direction='CREDIT'),0)::text FROM transfers t LEFT JOIN ledger_entries le ON le.transfer_id=t.id GROUP BY t.id HAVING COALESCE(SUM(le.amount_paise) FILTER(WHERE le.direction='DEBIT'),0)<>COALESCE(SUM(le.amount_paise) FILTER(WHERE le.direction='CREDIT'),0)", id);
      issues += jdbc.update("INSERT INTO reconciliation_issues(run_id,check_code,entity_id,expected_value,actual_value) SELECT ?,'STORED_BALANCE_MISMATCH',a.id,COALESCE(SUM(CASE WHEN le.direction='CREDIT' THEN le.amount_paise ELSE -le.amount_paise END),0)::text,a.balance_paise::text FROM accounts a LEFT JOIN ledger_entries le ON le.account_id=a.id GROUP BY a.id,a.balance_paise HAVING COALESCE(SUM(CASE WHEN le.direction='CREDIT' THEN le.amount_paise ELSE -le.amount_paise END),0)<>a.balance_paise", id);
      issues += jdbc.update("INSERT INTO reconciliation_issues(run_id,check_code,entity_id,expected_value,actual_value) SELECT ?,'WALLET_OVERDRAFT',a.id,'>=0',COALESCE(SUM(CASE WHEN le.direction='CREDIT' THEN le.amount_paise ELSE -le.amount_paise END),0)::text FROM accounts a LEFT JOIN ledger_entries le ON le.account_id=a.id WHERE a.account_type='WALLET' GROUP BY a.id HAVING COALESCE(SUM(CASE WHEN le.direction='CREDIT' THEN le.amount_paise ELSE -le.amount_paise END),0)<0", id);
      issues += jdbc.update("INSERT INTO reconciliation_issues(run_id,check_code,entity_id,expected_value,actual_value) SELECT ?,'TRANSFER_WITHOUT_ENTRIES',t.id,'at least 2',COUNT(le.id)::text FROM transfers t LEFT JOIN ledger_entries le ON le.transfer_id=t.id GROUP BY t.id HAVING COUNT(le.id)<2", id);
      jdbc.update("UPDATE reconciliation_runs SET finished_at=now(),checks_run=5,issues_found=?,status=? WHERE id=?", issues, issues == 0 ? "CLEAN" : "ISSUES", id);
      return id;
    });
  }

  private void issue(long run,String check,Long entity,String expected,String actual) {
    jdbc.update("INSERT INTO reconciliation_issues(run_id,check_code,entity_id,expected_value,actual_value) VALUES (?,?,?,?,?)",run,check,entity,expected,actual);
  }
}
