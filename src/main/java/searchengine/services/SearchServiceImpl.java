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

        // 1. Извлекаем и фильтруем леммы (убираем предлоги, союзы и т.д.)
        List<String> lemmas = textAnalyzer.extractLemmas(query);
        log.info("Извлеченные леммы из запроса: {}", lemmas)
        ;

        if (lemmas.isEmpty()) {
            log.warn("Не удалось выделить леммы из запроса '{}'", query);
            throw new IndexNotReadyException("Не удалось выделить леммы из запроса");
        }

        // Получаем список сайтов
        List<SiteModel> sites;
        if (siteUrl != null && !siteUrl.isEmpty()) {
            SiteModel site = siteRepository.findByUrl(siteUrl)
                    .orElseThrow(() -> new IndexNotReadyException("Сайт не проиндексирован: " + siteUrl));
            sites = List.of(site);
        } else {
            sites = siteRepository.findAll();
            if (sites.isEmpty()) {
                throw new IndexNotReadyException("Ни один сайт не проиндексирован");
            }
        }

        log.info("Сайты для поиска: {}", sites.stream().map(SiteModel::getUrl).toList());

        // Получаем LemmaModel для лемм, исключая слишком часто встречающиеся (частотные) леммы
        List<LemmaModel> filteredLemmas = new ArrayList<>();
        for (String lemmaText : lemmas) {
            for (SiteModel site : sites) {
                lemmaRepository.findByLemmaAndSite(lemmaText, site).ifPresent(lemma -> {
                    int totalPages = pageRepository.countBySiteModel(site);
                    double frequencyRatio = (double) lemma.getFrequency() / totalPages;
                    if (frequencyRatio < 0.7) {
                        filteredLemmas.add(lemma);
                        log.info("Лемма '{}' найдена для сайта '{}', частота={}, ratio={}",
                                lemma.getLemma(), site.getUrl(), lemma.getFrequency(), frequencyRatio);
                    } else {
                        log.info("Лемма '{}' слишком частая для сайта '{}', пропускаем (ratio={})",
                                lemma.getLemma(), site.getUrl(), frequencyRatio);
                    }
                });
            }
        }

        if (filteredLemmas.isEmpty()) {
            log.info("После фильтрации по частоте леммы не найдены");
            return emptyResponse(); // Не найдено ни одной релевантной леммы
        }

        // Сортируем леммы по редкости (по возрастанию частоты)
        filteredLemmas.sort(Comparator.comparingInt(LemmaModel::getFrequency));
        log.info("Леммы после сортировки по редкости: {}", filteredLemmas.stream().map(LemmaModel::getLemma).toList());

        // Получаем пересечение страниц, на которых встречаются все леммы
        Set<PageModel> resultPages = getCommonPages(filteredLemmas);
        log.info("Найдено страниц, содержащих все леммы: {}", resultPages.size());
        if (resultPages.isEmpty()) {
            return emptyResponse(); // Не найдено страниц, содержащих все леммы
        }

        // Рассчитываем релевантность для каждой страницы
        Map<PageModel, Float> relevanceMap = calculateRelevance(resultPages, filteredLemmas);

        // Нормализуем релевантность (делим на максимум)
        float maxRelevance = Collections.max(relevanceMap.values());
        log.info("Максимальная релевантность: {}", maxRelevance);

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<PageModel, Float> entry : relevanceMap.entrySet()) {
            PageModel page = entry.getKey();
            float relevance = entry.getValue() / maxRelevance;

            // Строим фрагмент текста (сниппет), где встречаются леммы
            String snippet = textAnalyzer.buildSnippet(page.getContent(), lemmas);

            // Извлекаем заголовок страницы
            String title = textAnalyzer.extractTitle(page.getContent());

            // Добавляем результат
            results.add(new SearchResult(
                    page.getSiteModel().getUrl(),
                    page.getSiteModel().getName(),
                    page.getPath(),
                    title,
                    snippet,
                    relevance
            ));

            log.info("Страница '{}' релевантность={}, title='{}'", page.getPath(), relevance, title);
        }

        // Сортировка результатов по релевантности (по убыванию)
        results.sort(Comparator.comparingDouble(SearchResult::getRelevance).reversed());

        // Постраничный вывод (offset + limit)
        int end = Math.min(offset + limit, results.size());
        List<SearchResult> pageResult = results.subList(Math.min(offset, results.size()), end);

        // Формируем успешный ответ
        SearchResponse response = new SearchResponse();
        response.setResult(true);
        response.setCount(results.size());  // общее количество найденных результатов (без учета offset/limit)
        response.setData(pageResult);

        log.info("Возвращено результатов: {}", pageResult.size());

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
