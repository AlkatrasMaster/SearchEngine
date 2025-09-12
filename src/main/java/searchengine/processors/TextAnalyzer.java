package searchengine.processors;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    // 🔹 1. Выделение лемм из строки (поисковый запрос)
    public List<String> extractLemmas(String text) {
        List<String> result = new ArrayList<>();
        String[] words = preprocessText(text);

        for (String word : words) {
            if (word.isBlank()) continue;

            try {
                List<String> morphInfo = luceneMorphology.getMorphInfo(word);
                if (isServiceWord(morphInfo)) continue;

                List<String> normalForms = luceneMorphology.getNormalForms(word);
                if (!normalForms.isEmpty()) {
                    result.add(normalForms.get(0));
                }
            } catch (Exception e) {
                log.debug("Не удалось разобрать слово '{}': {}", word, e.getMessage());
            }
        }
        return result;
    }

    public HashMap<String, Integer> analyseText(String text) {
        String[] words = preprocessText(text);
        HashMap<String, Integer> lemmas = new HashMap<>();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }

            try {
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
            } catch (Exception e) {
                log.debug("Не удалось проанализировать слово '{}': {}", word, e.getMessage());
            }
        }

        return lemmas;
    }

    // Строит сниппет — отрывок с выделением совпавших лемм
    public String buildSnippet(String htmlContent, List<String> queryLemmas) {
        String text = clearHtml(htmlContent);
        if(text.isBlank()) return "";

        // Разбиваем на слова
        String[] words = text.split("\\s+");

        // Приводим запрос к множеству для быстрого поиска
        Set<String> targetLemmas = new HashSet<>(queryLemmas);

        // Ищем первую позицию совпадения
        int matchIndex = -1;
        for (int i = 0; i < words.length; i++) {
            String cleaned = words[i].toLowerCase(Locale.ROOT).replaceAll("[^а-яё]", "");
            if (cleaned.isBlank()) continue;

            try {
                List<String> normalForms = luceneMorphology.getNormalForms(cleaned);
                if (!normalForms.isEmpty() && targetLemmas.contains(normalForms.get(0))) {
                    matchIndex = i;
                    break;
                }
            } catch (Exception e) {
                log.debug("Морфологическая ошибка на '{}': {}", cleaned, e.getMessage());
            }


        }

        // Если совпадений нет — вернуть первые 300 символов текста
        if (matchIndex == -1) {
            return text.length() > 300 ? text.substring(0, 300) + "..." : text;
        }

        // Определяем границы сниппета (~50 слов ≈ 3 строки)
        int start = Math.max(0, matchIndex - 25);
        int end = Math.min(words.length, matchIndex + 25);

        // Собираем сниппет
        StringBuilder snippet = new StringBuilder();
        for (int i = start; i < end; i++) {
            String originalWord = words[i];
            String cleaned = originalWord.toLowerCase(Locale.ROOT).replaceAll("[^а-яё]", "");

            boolean highlight = false;

            // Если слово в списке лемм — выделяем жирным
            try {
                List<String> normalForms = luceneMorphology.getNormalForms(cleaned);
                highlight = !normalForms.isEmpty() && targetLemmas.contains(normalForms.get(0));
            } catch (Exception ignored) {
            }

            if (highlight) {
                snippet.append("<b>").append(originalWord).append("</b>");
            } else {
                snippet.append(originalWord);
            }
            snippet.append(" ");
        }

        return snippet.toString().trim() + "...";

    }

    private String[] preprocessText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^а-яё\\s])", " ")
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

    // Очищает HTML от тегов
    public String clearHtml(String htmlContent) {
        if (htmlContent == null) return "";
        return htmlContent.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    // 🔹 Извлекает <title> страницы
    public String extractTitle(String htmlContent) {
        if (htmlContent == null) return "";
        Matcher matcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(htmlContent);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    // Проверка служебных слов
    private boolean isServiceWord(List<String> wordBaseForms) {
        return wordBaseForms.stream()
                .anyMatch(form -> {
                    String[] parts = form.split("\\|");
                    if (parts.length < 2) return false;
                    String partOfSpeech = parts[1].trim().toUpperCase();
                    for (String particle : particlesNames) {
                        if (partOfSpeech.contains(particle)) return true;
                    }
                    return false;
                });
    }
}
