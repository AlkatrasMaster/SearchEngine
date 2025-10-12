package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.dto.statistics.SearchResponse;
import searchengine.dto.statistics.SearchResult;
import searchengine.exceptions.IndexNotReadyException;
import searchengine.model.IndexModel;
import searchengine.model.LemmaModel;
import searchengine.model.PageModel;
import searchengine.model.SiteModel;
import searchengine.processors.TextAnalyzer;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService{

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final TextAnalyzer textAnalyzer;
    @Override
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        log.info("Поиск запроса '{}' для сайта '{}', offset={}, limit={}", query, siteUrl, offset, limit);

        List<String> lemmas = extractValidLemmas(query);
        List<SiteModel> sites = resolveSites(siteUrl);
        List<LemmaModel> filteredLemmas = filterLemmasByFrequency(lemmas, sites);

        if (filteredLemmas.isEmpty()) {
            log.info("После фильтрации по частоте леммы не найдены");
            return emptyResponse();
        }

        Set<PageModel> resultPages = getCommonPages(filteredLemmas);
        if (resultPages.isEmpty()) {
            return emptyResponse();
        }

        Map<PageModel, Float> relevanceMap = calculateRelevance(resultPages, filteredLemmas);
        List<SearchResult> results = buildSearchResults(relevanceMap, lemmas);

        return paginateResults(results, offset, limit);

    }

    // Извлечение лемм
    private List<String> extractValidLemmas(String query) {
        List<String> lemmas = textAnalyzer.extractLemmas(query);
        log.info("Извлеченные леммы: {}", lemmas);

        if (lemmas.isEmpty()) {
            throw new IndexNotReadyException("Не удалось выделить леммы из запроса");
        }
        return lemmas;
    }

    // Определение сайтов для поиска
    private List<SiteModel> resolveSites(String siteUrl) {
        if (siteUrl != null && !siteUrl.isEmpty()) {
            SiteModel site = siteRepository.findByUrl(siteUrl)
                    .orElseThrow(() -> new IndexNotReadyException("Сайт не проиндексирован: " + siteUrl));
            return List.of(site);
        }

        List<SiteModel> allSites = siteRepository.findAll();
        if (allSites.isEmpty()) {
            throw new IndexNotReadyException("Ни один сайт не проиндексирован");
        }

        log.info("Сайты для поиска: {}", allSites.stream().map(SiteModel::getUrl).toList());
        return allSites;
    }

    // Фильтрация лемм по частоте встречаемости
    private List<LemmaModel> filterLemmasByFrequency(List<String> lemmas, List<SiteModel> sites) {
        List<LemmaModel> filteredLemmas = new ArrayList<>();

        for (String lemmaText : lemmas) {
            for (SiteModel site : sites) {
                lemmaRepository.findByLemmaAndSite(lemmaText, site).ifPresent(lemma -> {
                    int totalPages = pageRepository.countBySiteModel(site);
                    double frequencyRatio = (double) lemma.getFrequency() / totalPages;

                    if (frequencyRatio < 0.7) {
                        filteredLemmas.add(lemma);
                        log.debug("Лемма '{}' оставлена для '{}', ratio={}", lemmaText, site.getUrl(), frequencyRatio);
                    }
                });
            }
        }

        filteredLemmas.sort(Comparator.comparingInt(LemmaModel::getFrequency));
        return filteredLemmas;
    }

    // Построение результатов
    private List<SearchResult> buildSearchResults(Map<PageModel, Float> relevanceMap, List<String> lemmas) {
        float maxRelevance = Collections.max(relevanceMap.values());
        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<PageModel, Float> entry : relevanceMap.entrySet()) {
            PageModel page = entry.getKey();
            float normalizedRelevance = entry.getValue() / maxRelevance;

            String snippet = textAnalyzer.buildSnippet(page.getContent(), lemmas);
            String title = textAnalyzer.extractTitle(page.getContent());

            results.add(new SearchResult(
                    page.getSiteModel().getUrl(),
                    page.getSiteModel().getName(),
                    page.getPath(),
                    title,
                    snippet,
                    normalizedRelevance
            ));
        }

        results.sort(Comparator.comparingDouble(SearchResult::getRelevance).reversed());
        return results;
    }

    // Постраничный вывод
    private SearchResponse paginateResults(List<SearchResult> results, int offset, int limit) {
        int end = Math.min(offset + limit, results.size());
        List<SearchResult> pageResults = results.subList(Math.min(offset, results.size()), end);

        SearchResponse response = new SearchResponse();
        response.setResult(true);
        response.setCount(results.size());
        response.setData(pageResults);

        log.info("Возвращено {} результатов из {}", pageResults.size(), results.size());
        return response;
    }


    //Возвращает страницы, содержащие все переданные леммы
    private Set<PageModel> getCommonPages(List<LemmaModel> lemmas, SiteModel site) {
        Set<PageModel> pages = new HashSet<>();
        boolean first = true;

        for (LemmaModel lemma : lemmas) {
            if (!lemma.getSite().equals(site)) continue;

            Set<PageModel> pagesWithLemma = indexRepository.findAllByLemma(lemma).stream()
                    .map(IndexModel::getPage)
                    .filter(p -> p.getSiteModel().equals(site)) // фильтрация по сайту
                    .collect(Collectors.toSet());

            log.info("Для леммы '{}' найдено {} страниц на сайте '{}'",
                    lemma.getLemma(), pagesWithLemma.size(), site.getUrl());

            if (first) {
                pages = pagesWithLemma;
                first = false;
            } else {
                pages.retainAll(pagesWithLemma);
            }

            if (pages.isEmpty()) {
                log.info("После пересечения страниц для леммы '{}' больше не осталось", lemma.getLemma());
                break;
            }
        }

        log.info("Итоговое количество страниц для сайта '{}': {}", site.getUrl(), pages.size());
        return pages;
    }

    // Перегруженный метод — поиск по всем сайтам
    private Set<PageModel> getCommonPages(List<LemmaModel> lemmas) {
        Set<PageModel> allPages = new HashSet<>();

        List<SiteModel> sites = siteRepository.findAll();
        for (SiteModel site : sites) {
            Set<PageModel> sitePages = getCommonPages(lemmas, site);
            allPages.addAll(sitePages); // объединение
            log.info("Добавлено {} страниц с сайта '{}'", sitePages.size(), site.getUrl());
        }

        log.info("Общее количество найденных страниц по всем сайтам: {}", allPages.size());
        return allPages;

    }

    /**
     * Рассчитывает абсолютную и относительную релевантность для каждой страницы
     * @param pages — найденные страницы
     * @param lemmas — список найденных лемм
     * @return Map с относительной релевантностью (0..1)
     */
    private Map<PageModel, Float> calculateRelevance(Set<PageModel> pages, List<LemmaModel> lemmas) {
        Map<PageModel, Float> absoluteRelevanceMap = new HashMap<>();

        // Абсолютная релевантность: сумма всех rank по леммам для каждой страницы
        for (PageModel page : pages) {
            float sumRank = 0f;
            for (LemmaModel lemma : lemmas) {
                Optional<IndexModel> index = indexRepository.findByPageAndLemma(page, lemma);
                sumRank += index.map(IndexModel::getRank).orElse(0f);
            }
            absoluteRelevanceMap.put(page, sumRank);
        }

        // Находим максимальную абсолютную релевантность
        float maxAbsRelevance = absoluteRelevanceMap.values()
                .stream()
                .max(Float::compare)
                .orElse(1f); // защита от деления на 0

        // Преобразуем абсолютную в относительную
        Map<PageModel, Float> relativeRelevanceMap = new HashMap<>();
        for (Map.Entry<PageModel, Float> entry : absoluteRelevanceMap.entrySet()) {
            float relative = entry.getValue() / maxAbsRelevance;
            relativeRelevanceMap.put(entry.getKey(), relative);
        }

        return relativeRelevanceMap;
    }


    // Возвращает пустой успешный ответ
    private SearchResponse emptyResponse() {
        SearchResponse response = new SearchResponse();
        response.setResult(true);
        response.setCount(0);
        response.setData(List.of());
        return response;

    }
}
