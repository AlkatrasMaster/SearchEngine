package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.CrawlerSettings;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.exceptions.IndexingAlreadyRunningException;
import searchengine.model.PageModel;
import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;
import searchengine.processors.PageProcessorTask;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;


import javax.transaction.Transactional;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class IndexingServiceImpl implements IndexingService{

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final HttpClient httpClient;
    private final CrawlerSettings crawlerSettings;
    private final SitesList sitesList;
    private final LemmaService lemmaService;
    private final AtomicBoolean isIndexingRunning = new AtomicBoolean(false);


    public IndexingServiceImpl(SiteRepository siteRepository, PageRepository pageRepository, CrawlerSettings crawlerSettings, SitesList sitesList, LemmaService lemmaService) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.crawlerSettings = crawlerSettings;
        this.sitesList = sitesList;
        this.lemmaService = lemmaService;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public boolean isIndexingRunning() {
        return isIndexingRunning.get();
    }


    // Запуск индексации
    @Override
    @Transactional
    public void startIndexing() throws InterruptedException {
        if (isIndexingRunning.get()) {
            throw new IndexingAlreadyRunningException("Индексация уже запущена");
        }

        isIndexingRunning.set(true);
        log.info("Начало индексации...");

        try {
            List<Site> configSites = sitesList.getSites();
            List<Thread> threads = new ArrayList<>();

            for (Site configSite : configSites) {
                Thread thread = new Thread(() -> {
                    SiteModel siteModel = null;
                    try {
                        siteRepository.deleteByUrl(configSite.getUrl());
                        pageRepository.deleteBySiteModelUrl(configSite.getUrl());

                        siteModel = new SiteModel();
                        siteModel.setUrl(configSite.getUrl());
                        siteModel.setName(configSite.getName());
                        siteModel.setStatus(IndexStatus.INDEXING);
                        siteModel.setStatusTime(LocalDateTime.now());
                        siteRepository.save(siteModel);

                        processPages(siteModel);

                        updateSiteStatus(siteModel, IndexStatus.INDEXED);
                        log.info("Индексация завершена для {}", configSite.getUrl());

                    } catch (Exception e) {
                        log.error("Ошибка при обработке сайта {}: {}", configSite.getUrl(), e.getMessage());
                        if (siteModel != null) {
                            updateSiteStatusAndError(siteModel, IndexStatus.FAILED, e.getMessage());
                        }
                    }
                });
                thread.setName("IndexingThread-" + configSite.getName());
                thread.start();
                threads.add(thread);
            }

            // Ждем завершения всех потоков
            for (Thread t : threads) {
                t.join();
            }

            log.info("Индексация завершена.");
        } finally {
            isIndexingRunning.set(false);
        }
    }



    private boolean hasActiveThread(List<Thread> threads) {
        return threads.stream()
                .anyMatch(thread -> thread.isAlive());
    }


    // Остановка индексации
    @Override
    @Transactional
    public Map<String, Object> stopIndexing() {
        if (!isIndexingRunning.get()) {
            return Map.of(
                    "result", false,
                    "error", "Индексация не запущена"
            );
        }

        log.info("Остановка индексации...");
        isIndexingRunning.set(false);

        // Получаем список сайтов, которые находятся в процессе индексации
        List<SiteModel> indexingSites = siteRepository.findByStatus(IndexStatus.INDEXED);

        // Обновляем статус всех сайтов в процессе индексации
        for (SiteModel site : indexingSites) {
            updateSiteStatusAndError(site, IndexStatus.FAILED, "Индексация остановлена пользователем");
        }

        return Map.of("result", true);
    }


    // Индексация одной веб-страницы
    @Override
    @Transactional
    public void indexPage(String url) throws Exception {
        // Проверяем, что URL принадлежит одному из сайтов из конфигурации
        Optional<Site> optionalSiteConfig = sitesList.getSites().stream()
                .filter(site -> url.startsWith(site.getUrl()))
                .findFirst();

        if (optionalSiteConfig.isEmpty()) {
            throw new IllegalArgumentException("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }

        Site siteConfig = optionalSiteConfig.get();

        // Ищем сайт в базе, либо создаём новую запись
        SiteModel siteModel = siteRepository.findByUrl(siteConfig.getUrl())
                .orElseGet(() -> {
                    SiteModel newSite = new SiteModel();
                    newSite.setUrl(siteConfig.getUrl());
                    newSite.setName(siteConfig.getName());
                    newSite.setStatus(IndexStatus.INDEXING);
                    newSite.setStatusTime(LocalDateTime.now());
                    return siteRepository.save(newSite);
                });

        String relativePath = getRelativePath(url, siteModel.getUrl());
        if (relativePath == null) {
            throw new IllegalArgumentException("Невозможно получить относительный путь страницы");
        }

        // Удаление старых лемм и индексов (если страница уже индексировалась)
        Optional<PageModel> optionalOldPage = pageRepository.findByPathAndSiteModel(relativePath, siteModel);
        optionalOldPage.ifPresent(lemmaService::removeLemmasAndIndexesForPage);

        // Загружаем и сохраняем содержимое страницы
        String content = fetchPageContentWithDelay(url);
        int code = getResponseCode(url);

        PageModel page = optionalOldPage.orElse(new PageModel());
        page.setSiteModel(siteModel);
        page.setPath(relativePath);
        page.setContent(content);
        page.setCode(code);

        pageRepository.save(page);


        // Обработка лемм и индексов
        lemmaService.processPageContent(page);

        // Обновление статуса сайта
        updateSiteStatus(siteModel, IndexStatus.INDEXED);


    }


    @Transactional
    private void processPages(SiteModel siteModel) {
        // Используем потокобезопасную очередь
        ConcurrentLinkedDeque<String> urlQueue = new ConcurrentLinkedDeque<>();
        urlQueue.add(siteModel.getUrl());

        ForkJoinPool pool = new ForkJoinPool();

        // 1 замечание исправлено. Исправлена проблема с инкрементацией currentDepth.
        // Создаем задачу с начальной глубиной 0
        PageProcessorTask task = new PageProcessorTask(urlQueue, siteModel, httpClient, pageRepository, siteRepository, crawlerSettings, isIndexingRunning, 0);

        pool.invoke(task);
    }
    private int getResponseCode(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (IOException | InterruptedException e) {
            log.warn("Не удалось получить код ответа для URL {}: {}", url, e.getMessage());
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    public void updateSiteStatusTime(SiteModel site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }


    private void updateSiteStatus(SiteModel site, IndexStatus status) {
        site.setStatus(status);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    public void updateSiteStatusAndError(SiteModel site, IndexStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    // Метод для вычисления относительного пути страницы
    private String getRelativePath(String absoluteUrl, String baseUrl) {
        try {
            URI baseUri = new URI(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            URI absoluteUri = new URI(absoluteUrl);

            if (!Objects.equals(baseUri.getHost(), absoluteUri.getHost())) {
                return null;
            }

            String basePath = baseUri.getPath();
            String absPath = absoluteUri.getPath();

            if (absPath.startsWith(basePath)) {
                return absPath.substring(basePath.length() - 1);
            } else {
                return absPath;
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Ошибка при обработке URL: " + e.getMessage());
        }
    }

    // Добавьте, если используете fetchPageContentWithDelay из PageProcessorTask,
    // либо реализуйте соответствующий метод здесь
    private String fetchPageContentWithDelay(String url) {
        try {
            long delay = 500 + (long) (Math.random() * 4500);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Поток был прерван");
        }
        return fetchPageContent(url);
    }

    // Новый метод
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
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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
}


