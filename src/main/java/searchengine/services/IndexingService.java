package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.CrawlerSettings;
import searchengine.config.Site;
import searchengine.exceptions.IndexingAlreadyRunningException;
import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;
import searchengine.processors.PageProcessorTask;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;


import javax.transaction.Transactional;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinPool;

@Service
@Slf4j
public class IndexingService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private volatile boolean isIndexingRunning = false;
    private final HttpClient httpClient;

    private final CrawlerSettings crawlerSettings;


    public IndexingService(SiteRepository siteRepository, PageRepository pageRepository, CrawlerSettings crawlerSettings) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.crawlerSettings = crawlerSettings;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Transactional
    public void startIndexing() throws InterruptedException {
        if (isIndexingRunning) {
            throw new IndexingAlreadyRunningException("Индексация уже запущена");
        }

        isIndexingRunning = true;
        log.info("Начало процесса индексации сайтов");

        try {
            // Получаем список сайтов из конфигурации
            List<SiteModel> sites = siteRepository.findAll();
            List<Thread> indexingThreads = new ArrayList<>();

            // Создаем и запускаем поток для каждого сайта
            for (SiteModel site : sites) {
                Thread thread = new Thread(() -> {
                    try {
                        processSite(site);
                    } catch (Exception e) {
                        log.error("Ошибка при обработке сайта {}: {}", site.getName(), e.getMessage());
                        updateSiteStatusAndError(site, IndexStatus.FAILED, e.getMessage());
                    }
                });
                thread.setName("IndexingThread-" + site.getName());
                thread.start();
                indexingThreads.add(thread);

            }

            // Проверяем статус потоков каждые 5 секунд
            while (hasActiveThread(indexingThreads)) {
                Thread.sleep(5000);
                log.info("Индексация продолжается...");
            }

            log.info("Процесс индексации завершен успешно");
        } catch (Exception e) {
            log.error("Ошибка при индексации сайтов", e);
            throw e;
        } finally {
            isIndexingRunning = false;
        }
    }

    private boolean hasActiveThread(List<Thread> threads) {
        return threads.stream()
                .anyMatch(thread -> thread.isAlive());
    }

    @Transactional
    public Map<String, Object> stopIndexing() {
        if (!isIndexingRunning) {
            return Map.of(
                    "result", false,
                    "error", "Индексация не запущена"
            );
        }

        log.info("Запрошена остановка процесса индексации");

        // Останавливаем все потоки
        isIndexingRunning = false;

        // Получаем список сайтов, которые находятся в процессе индексации
        List<SiteModel> indexingSites = siteRepository.findByStatus(IndexStatus.INDEXED);

        // Обновляем статус всех сайтов в процессе индексации
        for (SiteModel site : indexingSites) {
            updateSiteStatusAndError(
                    site,
                    IndexStatus.FAILED,
                    "Индексация остановлена пользователем"
            );
        }
        return Map.of("result", true);
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

        ForkJoinPool pool = new ForkJoinPool();
        pool.invoke(new PageProcessorTask(urlsToProcess, site, httpClient, pageRepository, siteRepository, crawlerSettings));
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

}
