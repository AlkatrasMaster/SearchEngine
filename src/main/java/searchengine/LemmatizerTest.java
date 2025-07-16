package searchengine;

import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import searchengine.processors.TextAnalyzer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class LemmatizerTest {
    public static void main(String[] args) throws IOException {

        TextAnalyzer analyzer = TextAnalyzer.getInstance();
        String text = "Повторное появление леопарда в Осетии позволяет предположить, что леопард постоянно обитает в некоторых районах Северного Кавказа.";
        Map<String, Integer> result = analyzer.analyseText(text);

        result.forEach((lemma, count) -> {
            System.out.printf("%s - %d%n", lemma, count);
        });
    }
//        try {
//            // Создаем экземпляр морфологии для русского языка
//            RussianLuceneMorphology luceneMorph = new RussianLuceneMorphology();
//
//            // Тестируем лемматизацию слова "леса"
//            List<String> wordBaseForms = luceneMorph.getNormalForms("леса");
//
//            // Выводим все возможные базовые формы слова
//            wordBaseForms.forEach(System.out::println);
//        } catch (IOException e) {
//            System.err.println("Ошибка при создании морфологического анализатора:");
//            System.err.println(e.getMessage());
//            e.printStackTrace();
//        }
//
//    }
}
