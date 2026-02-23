package br.com.roubometro.domain.exception;

public class PortalAccessException extends RoubometroException {
    public PortalAccessException(String message) { super(message); }
    public PortalAccessException(String message, Throwable cause) { super(message, cause); }
}
