package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import searchengine.config.CrawlerSettings;
import searchengine.config.Site;
import searchengine.config.SitesList;
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
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class IndexingServiceImpl implements IndexingService{

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private volatile boolean isIndexingRunning = false;
    private final HttpClient httpClient;
    private final CrawlerSettings crawlerSettings;
    private final SitesList sitesList;


    public IndexingServiceImpl(SiteRepository siteRepository, PageRepository pageRepository, CrawlerSettings crawlerSettings, SitesList sitesList) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.crawlerSettings = crawlerSettings;
        this.sitesList = sitesList;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public boolean isIndexingRunning() {
        return this.isIndexingRunning;
    }

    @Override
    public void startIndexing() throws InterruptedException {
        if (isIndexingRunning) {
            throw new IndexingAlreadyRunningException("Индексация уже запущена");
        }

        isIndexingRunning = true;
        log.info("Начало процесса индексации сайтов");

        try {
            // Получаем список сайтов из конфигурации
            List<Site> configSites = sitesList.getSites();
            List<Thread> indexingThreads = new ArrayList<>();

            // Создаем и запускаем поток для каждого сайта из конфигурации
            for (Site configSite : configSites) {
                Thread thread = new Thread(() -> {
                    SiteModel existingSite = null;
                    try {
                        // Проверяем, есть ли сайт в базе данных
                        existingSite = siteRepository.findByUrl(configSite.getUrl())
                                .orElseGet(() -> {
                                    SiteModel newSite = new SiteModel();
                                    newSite.setUrl(configSite.getUrl());
                                    newSite.setName(configSite.getName());
                                    return siteRepository.save(newSite);
                                });
                        processSite(existingSite);
                    } catch (Exception e) {
                        log.error("Ошибка при обработке сайта {}: {}",
                                configSite.getUrl(), e.getMessage());
                        updateSiteStatusAndError(existingSite, IndexStatus.FAILED,
                                e.getMessage());
                    }

                });
                thread.setName("IndexingThread-" + configSite.getName());
                thread.start();
                indexingThreads.add(thread);
            }

            // Проверяем статус потоков каждые 5 секунд
            while (hasActiveThread(indexingThreads)) {
                Thread.sleep(5000);
                log.info("Индексация продолжается...");
            }

            // После завершения индексации обновляем статус всех сайтов на INDEXED
            List<SiteModel> indexedSites = siteRepository.findByStatus(IndexStatus.INDEXING);
            for (SiteModel site : indexedSites) {
                updateSiteStatus(site, IndexStatus.INDEXED);
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
            siteRepository.deleteByUrl(site.getUrl());
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
            updateSiteStatus(newSite, IndexStatus.INDEXING);
        } catch (Exception e) {
            log.error("Ошибка при обработке сайта: {}, сообщение: {}", site.getUrl(), e.getMessage());
            if (newSite != null)
            {
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

    public void updateSiteStatusAndError(SiteModel site, IndexStatus status, String error) {
        site.setStatus(status);
        site.setLastError(error);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

}
