package searchengine.processors;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import searchengine.config.CrawlerSettings;
import searchengine.model.PageModel;
import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.IndexingService;

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
import java.util.concurrent.RecursiveAction;

@RequiredArgsConstructor
@Slf4j
public class PageProcessorTask extends RecursiveAction {

    private final Queue<String> urlsToProcess;
    private final SiteModel site;
    private final HttpClient httpClient;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final CrawlerSettings crawlerSettings;
    private static final int THRESHOLD = 100;
    private static final int MAX_DEPTH = 5;
    private int currentDepth = 0;


    @Override
    protected void compute() {
        if (urlsToProcess.size() <= THRESHOLD) {
            processSequentially();
            return;
        }

        // Разделяем задачу пополам
        Queue<String> halfUrls = new LinkedList<>();
        int count = urlsToProcess.size() /2;
        for (int i = 0; i < count && !urlsToProcess.isEmpty(); i++) {
            halfUrls.add(urlsToProcess.poll());
        }

        // Создаем подзадачи
        PageProcessorTask task1 = new PageProcessorTask(halfUrls, site, httpClient, pageRepository, siteRepository, crawlerSettings);
        PageProcessorTask task2 = new PageProcessorTask(urlsToProcess, site, httpClient, pageRepository, siteRepository, crawlerSettings);

        // Запускаем параллельно
        task1.fork();
        task2.compute();
        task1.join();

        return;

    }

    private void processSequentially() {
        while (!urlsToProcess.isEmpty() && currentDepth < MAX_DEPTH) {
            String currentUrl = urlsToProcess.poll();

            try {
                // Проверяем, была ли страница обработана
                if (!pageRepository.existsByPathAndSiteModel(currentUrl, site)) {

                    // Получаем содержимое страницы
                    String content = fetchPageContent(currentUrl);

                    // Сохраняем страницу в базу данных
                    PageModel page = new PageModel();
                    page.setSiteModel(site);
                    page.setPath(getRelativePath(currentUrl, site.getUrl()));
                    page.setContent(content);

                    try {

                        pageRepository.save(page);
                        // Обновляем время статуса сайта
                        updateSiteStatusTime(site);

                        // Добавляем найденные ссылки в очередь
                        List<String> newUrls = extractLinks(content, site.getUrl());
                        urlsToProcess.addAll(newUrls);
                    } catch (DataIntegrityViolationException e) {
                        log.warn("Страница {} уже существует в базе данных", currentUrl);
                    }
                }

            } catch (Exception e) {
                log.error("Ошибка при обработке страницы {}: {}", currentUrl, e.getMessage());
            }
        }
    }

    private String fetchPageContent(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", crawlerSettings.getUserAgent())
                .header("Referer", crawlerSettings.getReferrer())
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

    private void updateSiteStatusTime(SiteModel site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private List<String> extractLinks(String content, String baseUrl) {
        Document doc = Jsoup.parse(content);
        Elements links = doc.select("a[href]");
        List<String> urls = new ArrayList<>();

        for (Element link : links) {
            String href = links.attr("href");
            if (!href.isEmpty()) {
                try {
                    URI uri = new URI(href);
                    if (uri.isAbsolute()) {
                        urls.add(href);
                    } else {
                        urls.add(baseUrl + href);
                    }
                } catch (URISyntaxException e) {
                    log.warn("Некорректный URL: {}", href);
                }
            }
        }
        return urls;
    }

}
