package com.example.wallet.api;

import com.example.wallet.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public class WalletController {

  private final WalletService service;

  public WalletController(WalletService service) {
    this.service=service;
  }

  public record CreateUser(@NotBlank String name,@Email String email,@NotBlank String phone) {}

  public record OpenWallet(@NotBlank String name) {}

  public record TransferRequest(@Positive long amount_paise,long source_wallet_id,long destination_wallet_id,@Size(max=500) String note) {}

  public record TopupRequest(@Positive long amount_paise,long wallet_id,@Size(max=500) String note) {}

  public record WithdrawRequest(@Positive long amount_paise,long wallet_id,@Size(max=500) String note) {}

  @PostMapping("/users") ResponseEntity<?> createUser(@Valid @RequestBody CreateUser r) {
    return ResponseEntity.status(201).body(service.createUser(r.name(),r.email(),r.phone()));
  }

  @PostMapping("/users/{id}/wallets") ResponseEntity<?> openWallet(@PathVariable long id,@Valid @RequestBody OpenWallet r) {
    return ResponseEntity.status(201).body(service.openWallet(id,r.name()));
  }

  @GetMapping("/wallets/{id}/balance") Object balance(@PathVariable long id) {
    return service.balance(id);
  }

  @GetMapping("/wallets/{id}/statement") Object statement(@PathVariable long id,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
    return service.statement(id,limit,offset);
  }

  @PostMapping("/transfers/topup") ResponseEntity<?> topup(@RequestHeader("Idempotency-Key") UUID key,@RequestHeader("X-User-Id") long userId,@Valid @RequestBody TopupRequest r) {
    return service.idempotentPost(userId,key,r,()->service.topup(userId,r.wallet_id(),r.amount_paise(),r.note()));
  }

  @PostMapping("/transfers") ResponseEntity<?> transfer(@RequestHeader("Idempotency-Key") UUID key,@RequestHeader("X-User-Id") long userId,@Valid @RequestBody TransferRequest r) {
    return service.idempotentPost(userId,key,r,()->service.transfer(userId,r.source_wallet_id(),r.destination_wallet_id(),r.amount_paise(),r.note()));
  }

  @PostMapping("/transfers/withdraw") ResponseEntity<?> withdraw(@RequestHeader("Idempotency-Key") UUID key,@RequestHeader("X-User-Id") long userId,@Valid @RequestBody WithdrawRequest r) {
    return service.idempotentPost(userId,key,r,()->service.withdraw(userId,r.wallet_id(),r.amount_paise(),r.note()));
  }

  @GetMapping("/transfers/{id}") Object transfer(@PathVariable long id) {
    return service.getTransfer(id);
  }
}
