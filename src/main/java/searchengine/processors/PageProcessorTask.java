package searchengine.processors;

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
import searchengine.services.LemmaService;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class PageProcessorTask extends RecursiveAction {

    private final Queue<String> urlsToProcess;
    private final SiteModel site;
    private final HttpClient httpClient;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final CrawlerSettings crawlerSettings;
    private final AtomicBoolean isIndexingRunning;
    private final LemmaService lemmaService;
    private static final int THRESHOLD = 100;
    private static final int MAX_DEPTH = 5;

    private int currentDepth;

    public PageProcessorTask(Queue<String> urlsToProcess, SiteModel site, HttpClient httpClient, PageRepository pageRepository, SiteRepository siteRepository, CrawlerSettings crawlerSettings, AtomicBoolean isIndexingRunning, LemmaService lemmaService, int currentDepth) {
        this.urlsToProcess = urlsToProcess;
        this.site = site;
        this.httpClient = httpClient;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.crawlerSettings = crawlerSettings;
        this.isIndexingRunning = isIndexingRunning;
        this.lemmaService = lemmaService;
        this.currentDepth = currentDepth;
    }

    @Override
    protected void compute() {
        if (!isIndexingRunning.get()) {
            log.info("Индексация остановлена, завершаем задачу для сайта {}", site.getUrl());
            return;
        }

        if (urlsToProcess.size() <= THRESHOLD || currentDepth >= MAX_DEPTH) {
            processSequentially();
            return;
        }

        // Разделяем очередь на две части
        //
        ConcurrentLinkedDeque<String> halfUrls = new ConcurrentLinkedDeque<>();
        int count = urlsToProcess.size() / 2;
        for (int i = 0; i < count && !urlsToProcess.isEmpty(); i++) {
            String url = urlsToProcess.poll();
            if (url != null) {
                halfUrls.add(url);
            }
        }


        // Создаем подзадачи
        PageProcessorTask task1 = new PageProcessorTask(halfUrls, site, httpClient, pageRepository, siteRepository, crawlerSettings, isIndexingRunning, lemmaService, currentDepth + 1);
        PageProcessorTask task2 = new PageProcessorTask(urlsToProcess, site, httpClient, pageRepository, siteRepository, crawlerSettings, isIndexingRunning, lemmaService, currentDepth + 1);

        // Запускаем параллельно
        task1.fork();
        task2.compute();
        task1.join();

    }

    private void processSequentially() {
        while (!urlsToProcess.isEmpty() && currentDepth < MAX_DEPTH && isIndexingRunning.get()) {
            String currentUrl = urlsToProcess.poll();

            if (currentUrl == null || currentUrl.isEmpty()) {
                continue;
            }

            try {
                URI uri = new URI(currentUrl);
                String path = uri.getPath().toLowerCase();

                // Пропускаем URL изображений
                if (path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".png") || path.endsWith(".gif") ||
                    path.endsWith(".webp") || path.endsWith(".bmp")) {
                    continue;
                }

                String relativePath = getRelativePath(currentUrl, site.getUrl());
                if (relativePath == null) {
                    continue;
                }

                // Проверяем, существует ли страница в базе данных
                if (!pageRepository.existsByPathAndSiteModel(getRelativePath(currentUrl, site.getUrl()), site)) {
                    // Получаем содержимое страницы с задержкой для аккуратного обхода
                    String content = fetchPageContentWithDelay(currentUrl);

                    // Создаем новую страницу
                    PageModel page = new PageModel();
                    page.setSiteModel(site);
                    page.setPath(getRelativePath(currentUrl, site.getUrl()));
                    page.setContent(content);
                    page.setCode(getResponseCode(currentUrl));

                    try {
                        // Сохраняем страницу
                        pageRepository.save(page);
                        updateSiteStatusTime(site);

                        // обрабатываем текст страницы и сохраняем леммы + индекс
                        lemmaService.processPageContent(page);

                        // Извлекаем новые ссылки и добавляем в очередь
                        List<String> newUrls = extractLinks(content, site.getUrl());
                        // 2 замечание исправлено связанное с ошибкой в проверке при добавлении новых ссылок
                        // 3 замечание исправлено связанное с тем что getRelativePath может вернуть null
                        // Проверяем каждую новую ссылку перед добавлением
                        for (String newUrl : newUrls) {
                            String newRelativePath = getRelativePath(newUrl, site.getUrl());

                            if (newRelativePath == null &&
                                !urlsToProcess.contains(newUrl) &&
                                !pageRepository.existsByPathAndSiteModel(newRelativePath, site) &&
                                isUrlFromSameSite(newUrl, site.getUrl())) {
                                urlsToProcess.add(newUrl);
                            }
                        }

                        if (!newUrls.isEmpty() && currentDepth + 1 < MAX_DEPTH) {
                            PageProcessorTask subTask = new PageProcessorTask(
                                    new ConcurrentLinkedDeque<>(newUrls),
                                    site, httpClient, pageRepository, siteRepository,
                                    crawlerSettings, isIndexingRunning,
                                    lemmaService, currentDepth + 1
                            );
                            subTask.fork();
                            subTask.join();
                        }

                    } catch (DataIntegrityViolationException e) {
                        log.warn("Страница {} уже существует в базе данных", currentUrl);
                    }
                }
            } catch (Exception e) {
                log.error("Ошибка при обработке страницы {}: {}", currentUrl, e.getMessage());
                updateSiteStatusAndError(site, IndexStatus.FAILED, e.getMessage());
            }
        }
    }

    private String fetchPageContentWithDelay(String url) {
        // Задержка от 500 до 5000 мс случайным образом
        try {
            long delay = 500 + (long)(Math.random() * 4500);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Поток был прерван");
        }
        return fetchPageContent(url);
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
                if (!location.isEmpty()) {
                    return fetchPageContent(location);
                }
                throw new RuntimeException("Редирект без Location");
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
            URI baseUri = new URI(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            URI absoluteUri = new URI(absoluteUrl);

            if (!Objects.equals(baseUri.getHost(), absoluteUri.getHost())) {
                // Если URL не с того же сайта — возвращаем null или пустую строку
                return null;
            }

            String basePath = baseUri.getPath();
            String adsPath = absoluteUri.getPath();

//            if (adsPath.startsWith(basePath)) {
//                return adsPath.substring(basePath.length() - 1);
//            } else {
//                return adsPath;
//            }
            // Удаляем базовый путь, если он есть
            String relativePath = adsPath.startsWith(basePath)
                    ? adsPath.substring(basePath.length())
                    : adsPath;

            // Убедимся, что путь начинается с одного слэша и не содержит двойных
            relativePath = "/" + relativePath;
            relativePath = relativePath.replaceAll("/{2,}", "/");

            // Специальный случай — главная страница
            if (relativePath.equals("/")) {
                return "/";
            }
            return relativePath;
        } catch (URISyntaxException e) {
            throw new RuntimeException("Ошибка при обработке URL: " + e.getMessage());
        }
    }

    private boolean isUrlFromSameSite(String url, String baseUrl) {
        try {
            URI uri  = new URI(url);
            URI baseUri = new URI(baseUrl);

            return Objects.equals(uri.getHost(), baseUri.getHost());
        } catch (URISyntaxException e) {
            log.warn("Некорректный URL при проверке сайта: {}", url);
            return false;
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
            String href = link.attr("href");
            if (href == null || href.isEmpty()) {
                continue;
            }

            try {
                URI uri = new URI(href);
                if (uri.isAbsolute()) {
                    if (isUrlFromSameSite(href, baseUrl)) {
                        urls.add(href);
                    }
                } else {
                    // Обрабатываем относительные ссылки
                    String fullUrl = baseUrl.endsWith("/") ? baseUrl + href : baseUrl + "/" + href;
                    urls.add(fullUrl);
                }
            } catch (URISyntaxException e) {
                log.warn("Некорректный URL: {}", href);
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
            Thread.currentThread().interrupt();
            return 0; // или выберите другое значение по умолчанию
        }
    }
}
