package com.example.wallet.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

  private final ReconciliationService service;

  public ReconciliationScheduler(ReconciliationService service) {
    this.service=service;
  }

  @Scheduled(cron="${APP_RECONCILIATION_CRON:0 0 2 * * *}")
  public void nightly() {
    service.run();
  }
}
