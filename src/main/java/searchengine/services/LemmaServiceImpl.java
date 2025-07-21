package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.model.IndexModel;
import searchengine.model.LemmaModel;
import searchengine.model.PageModel;
import searchengine.processors.TextAnalyzer;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;

import javax.transaction.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class LemmaServiceImpl implements LemmaService{

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final TextAnalyzer textAnalyzer;

    /**
     * Обрабатывает HTML-страницу: выделяет леммы, сохраняет в lemma и index таблицы.
     */
    @Override
    public void processPageContent(PageModel pageModel) {
        String html = pageModel.getContent();
        String clearText = textAnalyzer.clearHtml(html);

        Map<String, Integer> lemmaCounts = textAnalyzer.analyseText(clearText);
        Set<String> existingLemmas = new HashSet<>();

        for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
            String lemmaText = entry.getKey();
            int countOnPage = entry.getValue();

            // Проверяем наличие леммы у сайта
            Optional<LemmaModel> optionalLemma = lemmaRepository.findByLemmaAndSite(lemmaText, pageModel.getSiteModel());

            LemmaModel lemmaModel;
            if (optionalLemma.isEmpty()) {
                // Если лемма новая – создаем и frequency = 1
                lemmaModel = new LemmaModel();
                lemmaModel.setLemma(lemmaText);
                lemmaModel.setSite(pageModel.getSiteModel());
                lemmaModel.setFrequency(1);
            } else {
                lemmaModel = optionalLemma.get();
                // Если лемма уже была, но еще не встречалась на этой странице
                if (!existingLemmas.contains(lemmaText)) {
                    lemmaModel.setFrequency(lemmaModel.getFrequency() + 1);
                }
            }

            lemmaRepository.save(lemmaModel);
            existingLemmas.add(lemmaText); // Отмечаем, что лемма уже встречалась на этой странице

            // Связка page-lemma -> index
            IndexModel index = new IndexModel();
            index.setPage(pageModel);
            index.setLemma(lemmaModel);
            index.setRank(countOnPage);
            indexRepository.save(index);
        }
    }

    @Override
    public void removeLemmasAndIndexesForPage(PageModel page) {
        List<IndexModel> indexModels = indexRepository.findAllByPage(page);

        for (IndexModel index : indexModels) {
            LemmaModel lemma = index.getLemma();
            lemma.setFrequency(lemma.getFrequency() - 1);

            if (lemma.getFrequency() <= 0) {
                lemmaRepository.delete(lemma);
            } else {
                lemmaRepository.save(lemma);
            }
        }

        indexRepository.deleteAllByPage(page);
    }
}
