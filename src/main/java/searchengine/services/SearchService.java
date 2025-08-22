package searchengine.services;

import searchengine.dto.statistics.SearchResponse;

public interface SearchService {

    SearchResponse search(String query, String site, int offset, int limit);
}
