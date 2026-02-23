package br.com.roubometro.domain.exception;

public class FileDownloadException extends RoubometroException {
    public FileDownloadException(String message) { super(message); }
    public FileDownloadException(String message, Throwable cause) { super(message, cause); }
}
