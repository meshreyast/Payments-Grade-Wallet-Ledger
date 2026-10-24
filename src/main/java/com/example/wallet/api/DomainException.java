package com.example.wallet.api;

public class DomainException extends RuntimeException {

  private final int status; private final String code;

  public DomainException(int status, String code, String message) {
    super(message); this.status=status; this.code=code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static DomainException badRequest(String c,String m) {
    return new DomainException(400,c,m);
  }

  public static DomainException conflict(String c,String m) {
    return new DomainException(409,c,m);
  }

  public static DomainException unprocessable(String c,String m) {
    return new DomainException(422,c,m);
  }

  public static DomainException notFound(String c,String m) {
    return new DomainException(404,c,m);
  }
}
