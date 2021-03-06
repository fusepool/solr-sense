/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox.solr;

import com.searchbox.commons.params.SenseParams;
import com.searchbox.lucene.QueryReductionFilter;
import com.searchbox.lucene.SenseQuery;
import com.searchbox.math.RealTermFreqVector;
import com.searchbox.utils.SolrCacheKey;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SimpleFacets;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocListAndSet;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.ReturnFields;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrReturnFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 *
 */
public class SenseQueryHandler extends RequestHandlerBase {

  private static final Logger LOGGER = LoggerFactory.getLogger(SenseQueryHandler.class);
  volatile long numRequests;
  volatile long numFiltered;
  volatile long totalTime;
  volatile long numErrors;
  volatile long numEmpty;
  volatile long numSubset;
  volatile long numTermsConsidered;
  volatile long numTermsUsed;
  private boolean keystate = true;

  private static NamedList<Explanation> getExplanations(Query query, DocList docs, SolrIndexSearcher searcher, IndexSchema schema) throws IOException {

    NamedList<Explanation> explainList = new SimpleOrderedMap<Explanation>();
    DocIterator iterator = docs.iterator();
    for (int i = 0; i < docs.size(); i++) {
      int id = iterator.nextDoc();

      Document doc = searcher.doc(id);
      String strid = schema.printableUniqueKey(doc);
      explainList.add(strid, searcher.explain(query, id));
    }
    return explainList;
  }

  @Override
  public void init(NamedList args) {
    super.init(args);
  }

  private boolean checkLicense(String key, String PRODUCT_KEY) {
    return com.searchbox.utils.DecryptLicense.checkLicense(key, PRODUCT_KEY);
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    NamedList<Object> timinginfo = new NamedList<Object>();
    numRequests++;
    long startTime = System.currentTimeMillis();
    long lstartTime = System.currentTimeMillis();


    if (!keystate) {
      LOGGER.error("License key failure, not performing sense query. Please email contact@searchbox.com for more information.");
      return;
    }

    boolean fromcache = false;

    try {
      SolrParams params = req.getParams();
      HashSet<String> toIgnore = new HashSet<String>();
      toIgnore.add("start");
      toIgnore.add("rows");
      toIgnore.add("fl");
      toIgnore.add("wt");
      toIgnore.add("indent");


      SolrCacheKey key = new SolrCacheKey(params, toIgnore);

      // Set field flags
      ReturnFields returnFields = new SolrReturnFields(req);
      rsp.setReturnFields(returnFields);
      int flags = 0;
      if (returnFields.wantsScore()) {
        flags |= SolrIndexSearcher.GET_SCORES;
      }

      String defType = params.get(QueryParsing.DEFTYPE, QParserPlugin.DEFAULT_QTYPE);
      String q = params.get(CommonParams.Q);
      Query query = null;
      QueryReductionFilter qr = null;
      List<Query> filters = new ArrayList<Query>();

      try {
        if (q != null) {
          QParser parser = QParser.getParser(q, defType, req);
          query = parser.getQuery();

        }

        String[] fqs = req.getParams().getParams(CommonParams.FQ);
        if (fqs != null && fqs.length != 0) {
          for (String fq : fqs) {
            if (fq != null && fq.trim().length() != 0) {
              QParser fqp = QParser.getParser(fq, null, req);
              filters.add(fqp.getQuery());
            }
          }
        }
      } catch (Exception e) {
        numErrors++;
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
      }


      int start = params.getInt(CommonParams.START, 0);
      int rows = params.getInt(CommonParams.ROWS, 10);

      SenseQuery slt = null;
      if (q == null) {
        numErrors++;
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "SenseLikeThis requires either a query (?q=) or text to find similar documents.");
      }

      timinginfo.add("Parse Query time", System.currentTimeMillis() - lstartTime);
      LOGGER.debug("Parsed Query Time:\t" + (System.currentTimeMillis() - lstartTime));
      lstartTime = System.currentTimeMillis();

      SolrIndexSearcher searcher = req.getSearcher();
      SolrCache sc = searcher.getCache("com.searchbox.sltcache");
      DocListAndSet sltDocs = null;
      if (sc != null) {
        //try to get from cache
        sltDocs = (DocListAndSet) sc.get(key.getSet());
      } else {
        LOGGER.error("com.searchbox.sltcache not defined, can't cache slt queries");
      }

      if (start + rows > 1000 || sltDocs == null || !params.getBool(CommonParams.CACHE, true)) { //not in cache, need to do search
        String CKBid = params.get(SenseParams.SENSE_CKB, SenseParams.SENSE_CKB_DEFAULT);
        String senseField = params.get(SenseParams.SENSE_FIELD, SenseParams.DEFAULT_SENSE_FIELD);
        RealTermFreqVector rtv = new RealTermFreqVector(q, SenseQuery.getAnalyzerForField(req.getSchema(), senseField));
        timinginfo.add("Make real term freq vector", System.currentTimeMillis() - lstartTime);
        lstartTime = System.currentTimeMillis();

        qr = new QueryReductionFilter(rtv, CKBid, searcher, senseField);
        qr.setNumtermstouse(params.getInt(SenseParams.SENSE_QR_NTU, SenseParams.SENSE_QR_NTU_DEFAULT));

        numTermsUsed += qr.getNumtermstouse();
        numTermsConsidered += rtv.getSize();


        qr.setThreshold(params.getInt(SenseParams.SENSE_QR_THRESH, SenseParams.SENSE_QR_THRESH_DEFAULT));
        qr.setMaxDocSubSet(params.getInt(SenseParams.SENSE_QR_MAXDOC, SenseParams.SENSE_QR_MAXDOC_DEFAULT));
        qr.setMinDocSetSizeForFilter(params.getInt(SenseParams.SENSE_MINDOC4QR, SenseParams.SENSE_MINDOC4QR_DEFAULT));

        timinginfo.add("Setup Sense query", System.currentTimeMillis() - lstartTime);
        LOGGER.debug("Setup Sense query:\t" + (System.currentTimeMillis() - lstartTime));
        lstartTime = System.currentTimeMillis();

        DocList subFiltered = qr.getSubSetToSearchIn(filters);
        timinginfo.add("Do Query Redux", System.currentTimeMillis() - lstartTime);
        LOGGER.debug("Do query redux:\t" + (System.currentTimeMillis() - lstartTime));
        lstartTime = System.currentTimeMillis();

        numFiltered += qr.getFiltered().docList.size();
        numSubset += subFiltered.size();
        LOGGER.info("Number of documents to search:\t" + subFiltered.size());

        slt = new SenseQuery(rtv, senseField, CKBid, params.getFloat(SenseParams.SENSE_WEIGHT, SenseParams.DEFAULT_SENSE_WEIGHT), null);
        LOGGER.debug("Setup sense query:\t" + (System.currentTimeMillis() - lstartTime));
        timinginfo.add("Setup sense query", System.currentTimeMillis() - lstartTime);
        lstartTime = System.currentTimeMillis();

        sltDocs = searcher.getDocListAndSet(slt, subFiltered, Sort.RELEVANCE, 0, 1000, flags);
        timinginfo.add("Do sense query", System.currentTimeMillis() - lstartTime);
        lstartTime = System.currentTimeMillis();

        LOGGER.debug("Adding this keyto cache:\t" + key.getSet().toString());
        searcher.getCache("com.searchbox.sltcache").put(key.getSet(), sltDocs);

      } else {
        fromcache = true;
        timinginfo.add("Getting from cache", System.currentTimeMillis() - lstartTime);
        LOGGER.debug("Got result from cache");
        lstartTime = System.currentTimeMillis();
      }


      if (sltDocs == null) {
        numEmpty++;
        sltDocs = new DocListAndSet(); // avoid NPE
      }
      rsp.add("response", sltDocs.docList.subset(start, rows));

      // --------- OLD CODE BELOW
      // maybe facet the results
      if (params.getBool(FacetParams.FACET, false)) {
        if (sltDocs.docSet == null) {
          rsp.add("facet_counts", null);
        } else {
          SimpleFacets f = new SimpleFacets(req, sltDocs.docSet, params);
          rsp.add("facet_counts", f.getFacetCounts());
        }
      }

      // Debug info, not doing it for the moment.
      boolean dbg = req.getParams().getBool(CommonParams.DEBUG_QUERY, false);

      boolean dbgQuery = false, dbgResults = false;
      if (dbg == false) {//if it's true, we are doing everything anyway.
        String[] dbgParams = req.getParams().getParams(CommonParams.DEBUG);
        if (dbgParams != null) {
          for (int i = 0; i < dbgParams.length; i++) {
            if (dbgParams[i].equals(CommonParams.QUERY)) {
              dbgQuery = true;
            } else if (dbgParams[i].equals(CommonParams.RESULTS)) {
              dbgResults = true;
            }
          }
        }
      } else {
        dbgQuery = true;
        dbgResults = true;
      }
      if (dbg == true) {
        try {
          NamedList dbgInfo = new SimpleOrderedMap();
          dbgInfo.add("Query freqs", slt.getAllTermsasString());
          dbgInfo.addAll(getExplanations(slt, sltDocs.docList.subset(start, rows), searcher, req.getSchema()));
          if (null != filters) {
            dbgInfo.add("filter_queries", req.getParams().getParams(CommonParams.FQ));
            List<String> fqs = new ArrayList<String>(filters.size());
            for (Query fq : filters) {
              fqs.add(QueryParsing.toString(fq, req.getSchema()));
            }
            dbgInfo.add("parsed_filter_queries", fqs);
          }
          if (null != qr) {
            dbgInfo.add("QueryReduction", qr.getDbgInfo());
          }
          if (null != slt) {
            dbgInfo.add("SLT", slt.getDbgInfo());

          }
          dbgInfo.add("fromcache", fromcache);
          rsp.add("debug", dbgInfo);
          timinginfo.add("Debugging parts", System.currentTimeMillis() - lstartTime);
          dbgInfo.add("timings", timinginfo);

        } catch (Exception e) {
          SolrException.log(SolrCore.log, "Exception during debug", e);
          rsp.add("exception_during_debug", SolrException.toStr(e));
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      numErrors++;
    } finally {
      totalTime += System.currentTimeMillis() - startTime;
    }
  }

  @Override
  public String getName() {
    return "SenseQueryHandler";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public String getDescription() {
    return "Searchbox handler based on latent semantics";
  }

  @Override
  public String getSource() {
    return "";
  }

  @Override
  public NamedList<Object> getStatistics() {

    NamedList all = new SimpleOrderedMap<Object>();
    all.add("requests", "" + numRequests);
    all.add("errors", "" + numErrors);
    all.add("totalTime(ms)", "" + totalTime);
    all.add("empty", "" + numEmpty);

    if (numRequests != 0) {
      all.add("averageFiltered", "" + (float) numFiltered / numRequests);
      all.add("averageSubset", "" + (float) numSubset / numRequests);

      all.add("totalTermsConsidered", numTermsConsidered);
      all.add("avgTermsConsidered", (float) numTermsConsidered / numRequests);

      all.add("totalTermsUsed", (float) numTermsConsidered);
      all.add("avgTermsUsed", (float) numTermsUsed / numRequests);

      all.add("avgTimePerRequest", "" + (float) totalTime / numRequests);
      all.add("avgRequestsPerSecond", "" + (float) numRequests / (totalTime * 0.001));
    } else {
      all.add("averageFiltered", "" + 0);
      all.add("averageSubset", "" + 0);
      all.add("avgTimePerRequest", "" + 0);
      all.add("avgRequestsPerSecond", "" + 0);
    }

    return all;
  }
}
