package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.IndexModel;
import searchengine.model.LemmaModel;
import searchengine.model.PageModel;

import java.util.List;
import java.util.Optional;

public interface IndexRepository extends JpaRepository<IndexModel, Integer> {
    List<IndexModel> findAllByPage(PageModel page);

    void deleteAllByPage(PageModel page);

    List<IndexModel> findAllByLemma(LemmaModel lemma);

    Optional<IndexModel> findByPageAndLemma(PageModel page, LemmaModel lemma);
}
