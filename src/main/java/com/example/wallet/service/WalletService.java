package com.example.wallet.service;

import com.example.wallet.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class WalletService {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;

  public WalletService(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper mapper) {
    this.jdbc=jdbc;this.tx=tx;this.mapper=mapper;
  }

  public Map<String,Object> createUser(String name,String email,String phone) {
    return tx.execute(s->{ Long id=jdbc.queryForObject("INSERT INTO users(name,email,phone,kyc_status) VALUES (?,?,?,'PENDING') RETURNING id",Long.class,name,email,phone); return Map.of("id",id,"name",name,"email",email,"phone",phone,"kyc_status","PENDING"); });
  }

  public Map<String,Object> openWallet(long userId,String name) {
    return tx.execute(s->{ requireUser(userId); Long id=jdbc.queryForObject("INSERT INTO accounts(user_id,name,account_type) VALUES (?,?,'WALLET') RETURNING id",Long.class,userId,name); return Map.of("wallet_id",id,"user_id",userId,"name",name,"currency","INR","balance_paise",0); });
  }

  public Map<String,Object> balance(long id) {
    try {
      return jdbc.queryForMap("SELECT id AS wallet_id,user_id,balance_paise,currency FROM accounts WHERE id=? AND account_type='WALLET'",id);
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw DomainException.notFound("WALLET_NOT_FOUND","Wallet not found.");
    }
  }

  public Map<String,Object> statement(long id,int limit,int offset) {
    if(limit<1||limit>200||offset<0) throw DomainException.badRequest("INVALID_PAGINATION","limit must be 1..200 and offset non-negative.");
    balance(id);
    return Map.of("wallet_id",id,"limit",limit,"offset",offset,"entries",jdbc.queryForList("SELECT le.id AS entry_id,le.transfer_id,le.direction,le.amount_paise,le.created_at,t.transfer_type,t.status,t.note FROM ledger_entries le JOIN transfers t ON t.id=le.transfer_id WHERE le.account_id=? ORDER BY le.created_at DESC,le.id DESC LIMIT ? OFFSET ?",id,limit,offset));
  }
  public Map<String,Object> topup(long userId,long wallet,long amount,String note) {
    return tx.execute(s->{
      lockOwnedWallet(wallet,userId);
      long cash=systemAccount("CASH_IN"); return post("TOPUP",cash,wallet,amount,note);
    });
  }

  public Map<String,Object> transfer(long userId,long source,long destination,long amount,String note) {
    return tx.execute(s->{

      lockOwnedWallet(source,userId); lockAccounts(source,destination); assertWallet(destination);

      long balance=jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?",Long.class,source);

      if(balance<amount) throw DomainException.unprocessable("INSUFFICIENT_FUNDS","The wallet does not have enough funds.");

      Map<String,Object> risk=jdbc.queryForMap("SELECT action,rule_code FROM risk_rules WHERE rule_code='LARGE_TRANSFER' AND enabled=true");

      Long threshold=jdbc.queryForObject("SELECT threshold_paise FROM risk_rules WHERE rule_code='LARGE_TRANSFER' AND enabled=true",Long.class);

      if(threshold!=null && amount>=threshold && "HOLD".equals(risk.get("action"))) {
        Long id=insertTransfer("PEER","HELD",amount,source,destination,note);
        jdbc.update("INSERT INTO transfer_status_audit(transfer_id,old_status,new_status,changed_by,reason) VALUES (?,NULL,'HELD','system',?)",id,"Risk rule: "+risk.get("rule_code"));
        jdbc.update("INSERT INTO risk_flags(transfer_id,rule_code) VALUES (?,?) ON CONFLICT DO NOTHING",id,risk.get("rule_code"));
        return response(id,"HELD",amount);
      }
      return post("PEER",source,destination,amount,note);
    });
  }

  public Map<String,Object> withdraw(long userId,long wallet,long amount,String note) {
    return tx.execute(s->{
      lockOwnedWallet(wallet,userId);
      long cash=systemAccount("CASH_OUT");
      lockAccounts(wallet,cash);
      long b=jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?",Long.class,wallet);
      if(b<amount) throw DomainException.unprocessable("INSUFFICIENT_FUNDS","The wallet does not have enough funds.");
      return post("WITHDRAWAL",wallet,cash,amount,note);
    });
  }

  public Map<String,Object> getTransfer(long id) {
    var rows=jdbc.queryForList("SELECT * FROM transfers WHERE id=?",id);
    if(rows.isEmpty()) throw DomainException.notFound("TRANSFER_NOT_FOUND","Transfer not found.");
    Map<String,Object> out=new LinkedHashMap<>(rows.get(0));
    out.put("entries",jdbc.queryForList("SELECT * FROM ledger_entries WHERE transfer_id=? ORDER BY id",id));
    out.put("status_history",jdbc.queryForList("SELECT * FROM transfer_status_audit WHERE transfer_id=? ORDER BY id",id));
    return out;
  }

  public ResponseEntity<?> idempotentPost(long userId,UUID key,Object request,java.util.function.Supplier<Map<String,Object>> operation) {

    String hash=sha256(json(request));

    try {
      Map<String,Object> body=tx.execute(s->{

        jdbc.update("INSERT INTO idempotency_keys(user_id,key,request_hash,status) VALUES (?,?,?,'RUNNING')",userId,key,hash);
        Map<String,Object> result=operation.get();
        jdbc.update("UPDATE idempotency_keys SET status='COMPLETED',response_status=201,response_body=?::jsonb,updated_at=now() WHERE user_id=? AND key=?",json(result),userId,key);
        return result; });

      return ResponseEntity.status(201).body(body);

    } catch(DuplicateKeyException duplicate) {

      return tx.execute(s->{
        Map<String,Object> old=jdbc.queryForMap("SELECT request_hash,status,response_status,response_body::text AS response_body FROM idempotency_keys WHERE user_id=? AND key=?",userId,key);
        if(!hash.equals(((String)old.get("request_hash")).trim())) throw DomainException.unprocessable("IDEMPOTENCY_KEY_REUSED","This key was already used with a different request body.");
        if(!"COMPLETED".equals(old.get("status"))) throw DomainException.conflict("REQUEST_IN_PROGRESS","Retry this request shortly.");
        try {
          Object body=mapper.readValue((String)old.get("response_body"),Object.class);
          return ResponseEntity.status(((Number)old.get("response_status")).intValue()).body(body);
        }
        catch(Exception e) {
          throw new IllegalStateException("Saved idempotency response could not be decoded",e);
        }
      });

    }
  }

  private Map<String,Object> post(String type,long source,long destination,long amount,String note) {
    if(amount<=0) throw DomainException.badRequest("INVALID_AMOUNT","amount_paise must be positive.");
    lockAccounts(source,destination);
    Map<String,Object> src=jdbc.queryForMap("SELECT account_type,balance_paise FROM accounts WHERE id=?",source);
    if("WALLET".equals(src.get("account_type"))&&((Number)src.get("balance_paise")).longValue()<amount) throw DomainException.unprocessable("INSUFFICIENT_FUNDS","The wallet does not have enough funds.");
    Long id=insertTransfer(type,"COMPLETED",amount,source,destination,note);
    jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,direction,amount_paise) VALUES (?,?,'DEBIT',?),(?,?,'CREDIT',?)",id,source,amount,id,destination,amount);
    jdbc.update("UPDATE accounts SET balance_paise=balance_paise-? WHERE id=?",amount,source);
    jdbc.update("UPDATE accounts SET balance_paise=balance_paise+? WHERE id=?",amount,destination);
    jdbc.update("INSERT INTO transfer_status_audit(transfer_id,old_status,new_status,changed_by,reason) VALUES (?,NULL,'COMPLETED','system','Transfer posted')",id);
    return response(id,"COMPLETED",amount);
  }

  private Long insertTransfer(String type,String status,long amount,long source,long dest,String note) {
    return jdbc.queryForObject("INSERT INTO transfers(transfer_type,status,amount_paise,source_account_id,destination_account_id,note,completed_at) VALUES (?,?,?,?,?,?,CASE WHEN ?='COMPLETED' THEN now() ELSE NULL END) RETURNING id",Long.class,type,status,amount,source,dest,note,status);
  }

  private Map<String,Object> response(long id,String status,long amount) {
    return Map.of("transfer_id",id,"status",status,"amount_paise",amount,"currency","INR");
  }

  private void requireUser(long id) {
    if(jdbc.queryForList("SELECT id FROM users WHERE id=?",id).isEmpty()) throw DomainException.notFound("USER_NOT_FOUND","User not found.");
  }

  private void lockOwnedWallet(long wallet,long user) {
    if(jdbc.queryForList("SELECT id FROM accounts WHERE id=? AND user_id=? AND account_type='WALLET' FOR UPDATE",wallet,user).isEmpty()) throw DomainException.notFound("WALLET_NOT_FOUND","Wallet not found for this user.");
  }

  private void assertWallet(long id) {
    if(jdbc.queryForList("SELECT id FROM accounts WHERE id=? AND account_type='WALLET' AND active=true",id).isEmpty()) throw DomainException.notFound("DESTINATION_WALLET_NOT_FOUND","Destination wallet not found.");
  }

  private void lockAccounts(long... ids) {
    Arrays.stream(ids).distinct().sorted().forEach(id->jdbc.queryForList("SELECT id FROM accounts WHERE id=? FOR UPDATE",id));
  }

  private long systemAccount(String type) {
    return jdbc.queryForObject("SELECT id FROM accounts WHERE account_type=? AND user_id IS NULL",Long.class,type);
  }

  private String json(Object o) {
    try {
      return mapper.writeValueAsString(o);
    } catch(Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch(Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
