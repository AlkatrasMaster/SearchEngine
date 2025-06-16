package searchengine.processors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.dao.DataIntegrityViolationException;
import searchengine.config.CrawlerSettings;
import searchengine.model.PageModel;
import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.*;
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
                if (!pageRepository.existsByPathAndSiteModel(currentUrl, site)) {
                    String content = fetchPageContent(currentUrl);
                    PageModel page = new PageModel();
                    page.setSiteModel(site);
                    page.setPath(getRelativePath(currentUrl, site.getUrl()));
                    page.setContent(content);
                    page.setCode(getResponseCode(currentUrl));

                    // Извлекаем новые ссылки и добавляем в очередь
                    List<String> newUrls = extractLinks(content, site.getUrl());
                }
            } catch (Exception e) {
                log.error("Ошибка при обработке страницы {}: {}", currentUrl, e.getMessage());
                updateSiteStatusAndError(site, IndexStatus.FAILED, e.getMessage());
            }
        }
    }

    private String fetchPageContent(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", Optional.ofNullable(crawlerSettings.getUserAgent())
                        .orElse("Mozilla/5.0"))
                .header("Referer", Optional.ofNullable(crawlerSettings.getReferrer())
                        .orElse("https://www.google.com"))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 301 || response.statusCode() == 302) {
                String location = response.headers().firstValue("Location").orElse("");
                return fetchPageContent(location); // Рекурсивный вызов для обработки редиректа
            }

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

    private void updateSiteStatusAndError(SiteModel site, IndexStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    private int getResponseCode(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (IOException | InterruptedException e) {
            log.warn("Не удалось получить код ответа для URL {}: {}", url, e.getMessage());
            return 0; // или выберите другое значение по умолчанию
        }
    }

}
