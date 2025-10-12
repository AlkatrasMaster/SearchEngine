package searchengine.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.statistics.SearchResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.exceptions.IndexNotReadyException;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@Slf4j
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    // ✅ Исправлено:
    public ApiController(StatisticsService statisticsService, IndexingService indexingService, SearchService searchService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.searchService = searchService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<Map<String, Object>>  startIndexing() {
        try {
            // Проверяем, не запущен ли процесс индексации
            if (indexingService.isIndexingRunning()) {
                Map<String, Object> response = new HashMap<>();
                response.put("result", false);
                response.put("error", "Индексация уже запущена");
                return ResponseEntity.badRequest().body(response);
            }

            // Запускаем индексацию в отдельном потоке
            CompletableFuture.runAsync(() -> {
                try {
                    indexingService.startIndexing();
                } catch (Exception e) {
                    log.error("Ошибка при запуске индексации: " + e.getMessage());
                }
            });

            // Немедленно возвращаем успешный ответ
            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Обработка других исключений
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Произошла ошибка при запуске индексации: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam("url") String url) {
        Map<String, Object> response = new HashMap<>();

        if (url == null || url.trim().isEmpty()) {
            response.put("result", false);
            response.put("error", "Адрес страницы не может быть пустым");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            indexingService.indexPage(url);

            response.put("result", true);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("result", false);
            response.put("error", "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Ошибка при индексации страницы: {}", e.getMessage());
            response.put("result", false);
            response.put("error", "Произошла ошибка при индексации страницы");
            return ResponseEntity.badRequest().body(response);
        }


    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        try {
            if (!indexingService.isIndexingRunning()) {
                Map<String, Object> response = new HashMap<>();
                response.put("result", false);
                response.put("error", "Индексация не запущена");
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> response = indexingService.stopIndexing();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "site", required = false) String site,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {

        if (query == null || query.trim().isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Задан пустой поисковый запрос");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            SearchResponse response = searchService.search(query, site, offset, limit);
            return ResponseEntity.ok(response);
        } catch (IndexNotReadyException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            log.error("Ошибка при выполнении поиска: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Произошла ошибка при выполнении поиска");
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

}
