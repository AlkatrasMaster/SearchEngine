package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.IndexModel;
import searchengine.model.PageModel;

import java.util.List;

public interface IndexRepository extends JpaRepository<IndexModel, Integer> {
    List<IndexModel> findAllByPage(PageModel page);

    void deleteAllByPage(PageModel page);
}
