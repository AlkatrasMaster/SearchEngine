package searchengine.processors;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class TextAnalyzer {
    private final LuceneMorphology luceneMorphology;
    private static final String WORD_TYPE_REGEX = "\\W\\w&&[^а-яА-Я\\s]";
    private static final String[] particlesNames = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ"};

    public static TextAnalyzer getInstance() throws IOException {
        LuceneMorphology morphology = new RussianLuceneMorphology();
        return new TextAnalyzer(morphology);

    }
    public TextAnalyzer(LuceneMorphology luceneMorphology) {
        this.luceneMorphology = luceneMorphology;
    }

    public HashMap<String, Integer> analyseText(String text) {
        String[] words = preprocessText(text);
        HashMap<String, Integer> lemmas = new HashMap<>();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }

            List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
            if (isServiceWord(wordBaseForms)) {
                continue;
            }

            List<String> normalForms = luceneMorphology.getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }

            String lemma = normalForms.get(0);
            lemmas.put(lemma, lemmas.getOrDefault(lemma, 0) + 1);
        }

        return lemmas;
    }

    private String[] preprocessText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^а-я\\s])", " ")
                .trim()
                .split("\\s+");
    }

    private boolean isParticle(List<String> wordBaseForms) {
        return wordBaseForms.stream()
                .anyMatch(this::hasParticleProperty);
    }

    private boolean hasParticleProperty(String wordBase) {
        for (String property : particlesNames) {
            if (wordBase.toUpperCase().contains(property)) {
                return true;
            }
        }
        return false;
    }

    public String clearHtml(String htmlContent) {
        if (htmlContent == null) {
            return "";
        }

        // Удаление HTML-тегов
        String cleanText = htmlContent.replaceAll("<[^>]+>", "");

        // Удаление множественных пробелов
        cleanText = cleanText.replaceAll("\\s+", " ");

        // Удаление начальных и конечных пробелов
        cleanText = cleanText.trim();

        return cleanText;
    }


    private boolean isServiceWord(List<String> wordBaseForms) {
        return wordBaseForms.stream()
                .anyMatch(form -> {
                    String[] parts = form.split("\\|");
                    if (parts.length < 2) {
                        return false;
                    }
                    String partOfSpeech = parts[1].trim();
                    return partOfSpeech.equals("СОЮЗ") ||
                           partOfSpeech.equals("МЕЖД") ||
                           partOfSpeech.equals("ПРЕДЛ");
                });
    }

}
