package br.com.roubometro.domain.exception;

public class DataIntegrityException extends RoubometroException {
    public DataIntegrityException(String message) { super(message); }
    public DataIntegrityException(String message, Throwable cause) { super(message, cause); }
}
