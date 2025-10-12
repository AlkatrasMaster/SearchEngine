package searchengine.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import searchengine.dto.statistics.ErrorResponse;
import searchengine.exceptions.IndexNotReadyException;
import searchengine.exceptions.IndexingAlreadyRunningException;

import javax.swing.text.html.parser.Entity;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    // 🔹 Индексация не готова
    @ExceptionHandler(IndexNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleIndexNotReady(IndexNotReadyException ex) {
        log.warn("Ошибка: {}", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(false, ex.getMessage()));
    }

    // 🔹 Индексация уже запущена
    @ExceptionHandler(IndexingAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleIndexingAlreadyRunning(IndexingAlreadyRunningException ex) {
        log.warn("Попытка запустить уже активную индексацию: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(false, ex.getMessage()));
    }

    // 🔹 Ошибка целостности данных (например, при сохранении дублирующихся записей)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.error("Ошибка базы данных: {}", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(false, ex.getMessage()));
    }


    // 🔹 Некорректные аргументы (например, неверный URL)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Некорректный аргумент: {}", ex.getMessage());
        return ResponseEntity
                .badRequest()
                .body(new ErrorResponse(false, ex.getMessage()));
    }

    // 🔹 Все остальные непредвиденные ошибки
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Необработанная ошибка: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(false, "Произошла внутренняя ошибка сервера."));
    }

}
