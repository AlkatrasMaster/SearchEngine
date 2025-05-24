package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.PageModel;

import java.util.List;

public interface PageRepository extends JpaRepository<PageModel, Integer> {

    List<PageModel> findBySiteModelId(Integer siteId);

    void deleteBySiteModelUrl(String siteUrl);

    PageModel findByPathAndSiteModelId(String path, Integer siteModelId);

}
