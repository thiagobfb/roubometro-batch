package br.com.roubometro.domain.exception;

public class CsvParsingException extends RoubometroException {
    public CsvParsingException(String message) { super(message); }
    public CsvParsingException(String message, Throwable cause) { super(message, cause); }
}
