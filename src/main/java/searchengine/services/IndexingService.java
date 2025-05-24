package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.exceptions.IndexingAlreadyRunningException;
import searchengine.model.PageModel;
import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import javax.swing.text.html.parser.DocumentParser;
import javax.transaction.Status;
import javax.transaction.Transactional;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

@Service
@Slf4j
public class IndexingService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SitesList sitesList;
    private volatile boolean isIndexingRunning = false;
    private final HttpClient httpClient;


    public IndexingService(SiteRepository siteRepository, PageRepository pageRepository, SitesList sitesList, HttpClient httpClient, DocumentParser documentParser) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.sitesList = sitesList;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Transactional
    public void startIndexing() {
        if (isIndexingRunning) {
            throw new IndexingAlreadyRunningException("Индексация уже запущена");
        }

        isIndexingRunning = true;
        log.info("Начало процесса индексации сайтов");

        try {
            // Получаем список сайтов из конфигурации
            List<SiteModel> sites = siteRepository.findAll();

            // Обрабатываем каждый сайт
            for (SiteModel site : sites) {
                processSite(site);
            }

            log.info("Процесс индексации завершен успешно");
        } catch (Exception e) {
            log.error("Ошибка при индексации сайтов", e);
            throw e;
        } finally {
            isIndexingRunning = false;
        }
    }

    private void processSite(SiteModel site) {
        SiteModel newSite = null;
        try {
            // Удаляем существующие страницы сайта
            pageRepository.deleteBySiteModelUrl(site.getUrl());

            // Создаем новую запись сайта со статусом INDEXING
            newSite = new SiteModel();
            newSite.setUrl(site.getUrl());
            newSite.setName(site.getName());
            newSite.setStatus(IndexStatus.INDEXED);
            newSite.setStatusTime(LocalDateTime.now());

            siteRepository.save(newSite);

            // Обходим все страницы сайта
            processPages(newSite);

            // Обновляем статус на INDEXED
            updateSiteStatus(newSite, IndexStatus.INDEXED);
        } catch (Exception e) {
            // Обновляем статус на FAILED и сохраняем ошибку
            if (newSite != null) {
                updateSiteStatusAndError(newSite, IndexStatus.FAILED, e.getMessage());
            }
            throw e;
        }
    }

    private void processPages(SiteModel site) {
        Queue<String> urlsToProcess = new LinkedList<>();
        urlsToProcess.add(site.getUrl());

        while (!urlsToProcess.isEmpty()) {
            String currentUrl = urlsToProcess.poll();

            try {
                // Получаем содержимое страницы
                String content = fetchPageContent(currentUrl);

                // Сохраняем страницу в базу данных
                PageModel page = new PageModel();
                page.setSiteModel(site);
                page.setPath(getRelativePath(currentUrl, site.getUrl()));
                page.setContent(content);
                pageRepository.save(page);

                // Обновляем время статуса сайта
                updateSiteStatusTime(site);

                // Добавляем найденные ссылки в очередь
                urlsToProcess.addAll(extractLinks(content, site.getUrl()));

            } catch (Exception e) {
                log.error("Ошибка при обработке страницы {}: {}", currentUrl, e.getMessage());
                throw e;
            }
        }
    }

    private String fetchPageContent(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Ошибка при получении страницы: " + response.statusCode());
            }

            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Ошибка при получении содержимого страницы: " + e.getMessage());
        }
    }

    private String getRelativePath(String absoluteUrl, String baseUrl) {
        try {
            URI absoluteUri = new URI(absoluteUrl);
            URI baseUri = new URI(baseUrl);
            return absoluteUri.relativize(baseUri).getPath();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Ошибка при обработке URL: " + e.getMessage());
        }
    }

    private List<String> extractLinks(String content, String baseUrl) {
        List<String> links = new ArrayList<>();
        // Реализация извлечения ссылок из HTML
        return links;
    }

    private void updateSiteStatus(SiteModel site, IndexStatus status) {
        site.setStatus(status);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private void updateSiteStatusAndError(SiteModel site, IndexStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private void updateSiteStatusTime(SiteModel site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }
}
