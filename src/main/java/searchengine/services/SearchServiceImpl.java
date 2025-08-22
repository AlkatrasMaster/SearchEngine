package searchengine.services;

import lombok.RequiredArgsConstructor;
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
public class SearchServiceImpl implements SearchService{

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final TextAnalyzer textAnalyzer;
    @Override
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        // 1. Извлекаем и фильтруем леммы (убираем предлоги, союзы и т.д.)
        List<String> lemmas = textAnalyzer.extractLemmas(query);

        if (lemmas.isEmpty()) {
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

        // Получаем LemmaModel для лемм, исключая слишком часто встречающиеся (частотные) леммы
        List<LemmaModel> filteredLemmas = new ArrayList<>();
        for (String lemmaText : lemmas) {
            for (SiteModel site : sites) {
                lemmaRepository.findByLemmaAndSite(lemmaText, site).ifPresent(lemma -> {
                    int totalPages = pageRepository.countBySiteModel(site);
                    // Исключаем "шумные" леммы (встречаются на более чем 50% страниц)
                    if ((double) lemma.getFrequency() / totalPages < 0.5) {
                        filteredLemmas.add(lemma);
                    }
                });
            }
        }

        if (filteredLemmas.isEmpty()) {
            return emptyResponse(); // Не найдено ни одной релевантной леммы
        }

        // Сортируем леммы по редкости (по возрастанию частоты)
        filteredLemmas.sort(Comparator.comparingInt(LemmaModel::getFrequency));

        // Получаем пересечение страниц, на которых встречаются все леммы
        Set<PageModel> resultPages = getCommonPages(filteredLemmas);
        if (resultPages.isEmpty()) {
            return emptyResponse(); // Не найдено страниц, содержащих все леммы
        }

        // Рассчитываем релевантность для каждой страницы
        Map<PageModel, Float> relevanceMap = calculateRelevance(resultPages, filteredLemmas);

        // Нормализуем релевантность (делим на максимум)
        float maxRelevance = Collections.max(relevanceMap.values());

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

            if (first) {
                pages = pagesWithLemma;
                first = false;
            } else {
                pages.retainAll(pagesWithLemma);
            }

            if (pages.isEmpty()) break;
        }
        return pages;
    }

    // Перегруженный метод — поиск по всем сайтам
    private Set<PageModel> getCommonPages(List<LemmaModel> lemmas) {
        Set<PageModel> result = new HashSet<>();
        boolean first = true;

        List<SiteModel> sites = siteRepository.findAll();

        for (SiteModel site : sites) {
            Set<PageModel> sitePages = getCommonPages(lemmas, site);

            if (first) {
                result = sitePages;
                first = false;
            } else {
                result.addAll(sitePages);
            }
        }

        return result;
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
