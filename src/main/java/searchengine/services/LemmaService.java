package searchengine.services;

import searchengine.model.PageModel;

public interface LemmaService {
    void processPageContent(PageModel page);

    void removeLemmasAndIndexesForPage(PageModel page);
}
