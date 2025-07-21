package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final SitesList sitesList;

    @Override
    public StatisticsResponse getStatistics() {
        List<DetailedStatisticsItem> detailedList = new ArrayList<>();
        TotalStatistics total = new TotalStatistics();

        List<Site> configuredSites = sitesList.getSites(); // из application.yaml
        List<SiteModel> siteModelsInDb = siteRepository.findAll(); // из БД

        total.setSites(configuredSites.size());
        int totalPages = 0;
        int totalLemmas = 0;
        boolean isIndexing = false;

        for (Site site : configuredSites) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());

            // Пытаемся найти в БД по URL
            Optional<SiteModel> siteModelOpt = siteModelsInDb.stream()
                    .filter(s -> s.getUrl().equals(site.getUrl()))
                    .findFirst();

            if (siteModelOpt.isPresent()) {
                SiteModel siteModel = siteModelOpt.get();

                int pages = pageRepository.countBySiteModel(siteModel);
                int lemmas = lemmaRepository.countBySite(siteModel);

                item.setStatus(siteModel.getStatus().name());
                item.setStatusTime(siteModel.getStatusTime().atZone(ZoneId.systemDefault()).toEpochSecond());
                item.setPages(pages);
                item.setLemmas(lemmas);

                if (siteModel.getLastError() != null && !siteModel.getLastError().isBlank()) {
                    item.setError(siteModel.getLastError());
                }

                totalPages += pages;
                totalLemmas += lemmas;

                if (siteModel.getStatus() == IndexStatus.INDEXING) {
                    isIndexing = true;
                }

            } else {
                // Сайт не индексировался ещё
                item.setStatus("NOT_INDEXED");
                item.setPages(0);
                item.setLemmas(0);
                item.setStatusTime(System.currentTimeMillis() / 1000);
            }

            detailedList.add(item);
        }

        total.setPages(totalPages);
        total.setLemmas(totalLemmas);
        total.setIndexing(isIndexing);

        StatisticsData statisticsData = new StatisticsData();
        statisticsData.setTotal(total);
        statisticsData.setDetailed(detailedList);

        StatisticsResponse response = new StatisticsResponse();
        response.setResult(true);
        response.setStatistics(statisticsData);

        return response;
    }
}
