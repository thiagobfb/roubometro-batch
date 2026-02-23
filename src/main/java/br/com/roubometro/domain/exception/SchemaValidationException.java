package br.com.roubometro.domain.exception;

public class SchemaValidationException extends RoubometroException {
    public SchemaValidationException(String message) { super(message); }
    public SchemaValidationException(String message, Throwable cause) { super(message, cause); }
}
