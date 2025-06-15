package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.IndexingServiceImpl;
import searchengine.services.StatisticsService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

    public ApiController(StatisticsService statisticsService, IndexingServiceImpl indexingServiceImpl, IndexingService indexingService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
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

            // Запускаем процесс индексации
            indexingService.startIndexing();

            // Создаем успешный ответ
            Map<String, Object> responce = new HashMap<>();
            responce.put("result", true);
            return ResponseEntity.ok(responce);
        } catch (Exception e) {
            // Обработка других исключений
            Map<String, Object> responce = new HashMap<>();
            responce.put("result", false);
            responce.put("error", "Произошла ошибка при запуске индексации: " + e.getMessage());
            return ResponseEntity.badRequest().body(responce);
        }
    }

//    @GetMapping("/stopIndexing")
//    public ResponseEntity<Map<String, Object>> stopIndexing() {
//        try {
//            Map<String, Object> responce = indexingServiceImpl.stopIndexing();
//            return ResponseEntity.ok(responce);
//        } catch (Exception e) {
//            Map<String, Object> responce = new HashMap<>();
//            responce.put("result", false);
//            responce.put("error", e.getMessage());
//            return ResponseEntity.badRequest().body(responce);
//        }
//
//    }

}
