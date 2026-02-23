package br.com.roubometro.domain.exception;

public class RoubometroException extends RuntimeException {
    public RoubometroException(String message) { super(message); }
    public RoubometroException(String message, Throwable cause) { super(message, cause); }
}
