package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import searchengine.config.CrawlerSettings;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.exceptions.IndexingAlreadyRunningException;
import searchengine.exceptions.IndexingException;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class IndexingServiceImpl implements IndexingService{

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final HttpClient httpClient;
    private final CrawlerSettings crawlerSettings;
    private final SitesList sitesList;
    private final AtomicBoolean isIndexingRunning = new AtomicBoolean(false);


    public IndexingServiceImpl(SiteRepository siteRepository, PageRepository pageRepository, CrawlerSettings crawlerSettings, SitesList sitesList) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.crawlerSettings = crawlerSettings;
        this.sitesList = sitesList;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public boolean isIndexingRunning() {
        return isIndexingRunning.get();
    }

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



//    private void processSite(SiteModel site) {
//        SiteModel newSite = null;
//        try {
//            // Удаляем существующие страницы сайта
//            siteRepository.deleteByUrl(site.getUrl());
//            pageRepository.deleteBySiteModelUrl(site.getUrl());
//
//            // Создаем новую запись сайта со статусом INDEXING
//            newSite = new SiteModel();
//            newSite.setUrl(site.getUrl());
//            newSite.setName(site.getName());
//            newSite.setStatus(IndexStatus.INDEXED);
//            newSite.setStatusTime(LocalDateTime.now());
//
//            siteRepository.save(newSite);
//
//            // Обходим все страницы сайта
//            processPages(newSite);
//
//            // Обновляем статус на INDEXED
//            updateSiteStatus(newSite, IndexStatus.INDEXING);
//        } catch (Exception e) {
//            log.error("Ошибка при обработке сайта: {}, сообщение: {}", site.getUrl(), e.getMessage());
//            if (newSite != null)
//            {
//                updateSiteStatusAndError(newSite, IndexStatus.FAILED, e.getMessage());
//            }
//        }
//    }



    @Transactional
    private void processPages(SiteModel siteModel) {
        // Используем потокобезопасную очередь
        Queue<String> urlQueue = new ConcurrentLinkedDeque<>();
        urlQueue.add(siteModel.getUrl());

        ForkJoinPool pool = new ForkJoinPool();

        // Создаем задачу с начальной глубиной 0
        PageProcessorTask task = new PageProcessorTask(urlQueue, siteModel, httpClient, pageRepository, siteRepository, crawlerSettings, isIndexingRunning);

        pool.invoke(task);
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


}
