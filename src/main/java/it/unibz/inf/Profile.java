package it.unibz.inf;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;


public class Profile {
  private final String USER_AGENT = "Mozilla/5.0";
  String name;
  String description;
  WikidataClass wikidataclass;
  List<Attribute> attrs;
  List<Facet> facets;
  int totalQueries = 0;
  String endpoint = "https://query.wikidata.org/sparql";
  QueryExecution qe;
  ResultSet rs;
  QuerySolution qs;
  Connection connection;

  public Profile(String name, String description, WikidataClass wikidataclass, List<Attribute> attrs, List<Facet> facets){
      this.name = name;
      this.description = description;
      this.wikidataclass = wikidataclass;
      this.attrs = attrs;
      this.facets = facets;
  }

  public String toString(){
    return name + " " + description + " " + wikidataclass.toString() + " " + attrs.toString() + " " + facets.toString();
  }

  public List<List<Value>> getAllPossibleFacetValueCombinations() {
    System.out.println("Combining all facet-values...");
    List<List<Value>> elements = new ArrayList();

    for(Facet currentFacet : this.facets){
      elements.add(currentFacet.values);
    }
    // Create a list of immutableLists of strings
    List<ImmutableList<Value>> immutableElements = makeListofImmutable(elements);

    // Use Guava's Lists.cartesianProduct, since Guava 19
    List<List<Value>> cartesianProduct = Lists.cartesianProduct(immutableElements);

    return cartesianProduct;
  }

  public void populateProfileTable() {
    for (List<Value> facetValueCombination : getAllPossibleFacetValueCombinations()){
      System.out.println("current facet value combination: " + facetValueCombination);
      populateThisRowWithSeveralQueries(facetValueCombination);
    }

  }

  public ResultSet getResultSet(String query){
    ResultSet rs = null;
    int maxTries = 10;
    int count = 0;
    int sleepTime = 3000;
    boolean keepGoing = true;
    //this code to retry queries when server kicks out is still not tested if it actually works
    //consult Simon
    while(keepGoing){
      try{
        System.out.println("trying to execute endpoint query");
        rs = QueryExecutionFactory.sparqlService(endpoint,query).execSelect();
        keepGoing = false;
      } catch (Exception e){
        e.printStackTrace();
        System.out.println("The server seems to have kicked us out...");
        System.out.println("Sleeping for " + sleepTime);
        if(++count == maxTries) keepGoing = false;
        try {
          Thread.sleep(sleepTime);
          sleepTime = sleepTime +1000;
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }

      }
    }
    return rs;
  }

  public void getAllResults(List<Value> queryParameters){
    System.out.println("----------------------------------------");
    PreparedStatement ps = null;
    String sqlCode = "insert into "+this.getNameforDB()+"(";
    for (Facet currentFacet : this.facets){
      sqlCode += currentFacet.attr.code +", ";
    }
    sqlCode += " total, ";
    for(Attribute currentAttribute: this.attrs){
      sqlCode += currentAttribute.code+"count, ";
    }
    List<Integer> pSlotsNumbers = new ArrayList();
    for(int i=0;i<=attrs.size();i++){
      sqlCode += "p"+(int)(((float)i/attrs.size())*100)+", ";
      pSlotsNumbers.add((int)(((float)i/attrs.size())*100));
      sqlCode += "e"+(int)(((float)i/attrs.size())*100)+", ";
      sqlCode += "l"+(int)(((float)i/attrs.size())*100)+", ";
      sqlCode += "bin_vec"+(int)(((float)i/attrs.size())*100)+", ";
    }
    sqlCode = sqlCode.substring(0,sqlCode.length()-2);
    sqlCode +=")";

    sqlCode += " values('";

    for(Value currentvalue : queryParameters){

      sqlCode+= currentvalue.code+"', '";
    }

    sqlCode=sqlCode.substring(0, sqlCode.length()-1);
    //legacy code, line below
    //String totalcount = getResultSet(populateTotalColumn(queryParameters)).next().get("count").asLiteral().getValue().toString();
    JSONObject responseObj = getQueryResult(populateTotalColumn(queryParameters));
    String totalcount = null;
    try {
      totalcount = responseObj.getJSONArray("bindings")
                .getJSONObject(0)
                .getJSONObject("count")
                .getString("value");
    } catch (JSONException e) {
      System.out.println("Error: totalcount not found in JSONresponseObj");
      //totalcount = 0; // ?? we might default to value 0 in order not to crash the db statement
      //consultation point
      e.printStackTrace();
    }
    System.out.println("Total count we got from wolfgang JSON response object extraction: " + totalcount);
    sqlCode += totalcount+", ";
    for(String currentAttrQueryString : populateAttribCount(queryParameters)){
      responseObj = getQueryResult(currentAttrQueryString);
      String currAttrCount = null;
      try {
        currAttrCount = responseObj.getJSONArray("bindings")
                .getJSONObject(0)
                .getJSONObject("count")
                .getString("value");
      } catch (JSONException e) {
        System.out.println("Error: currAttrCount not found in JSONresponseObj");
        //currAttrCount = 0;
        e.printStackTrace();
      }
      System.out.println("currAttrCount we got from wolfgang JSON response object extraction: " + currAttrCount);
      sqlCode+=currAttrCount+", ";
    }

    Map<Integer, Integer> pSlots = new HashMap();
    for(Integer currPslot : pSlotsNumbers){
      pSlots.put(currPslot,0);
    }

    responseObj = getQueryResult(populatePercentageSlots(queryParameters));
    System.out.println("percentage slots-->: " + responseObj);
    try {
      System.out.println("test json iterable: " + responseObj.getJSONArray("bindings").length());
      for(int i=0;i<responseObj.getJSONArray("bindings").length();i++){
        String completenessPercentageSlot = responseObj.getJSONArray("bindings")
                .getJSONObject(i)
                .getJSONObject("completenessPercentage")
                .getString("value");
        String countItemSlot = responseObj.getJSONArray("bindings")
                .getJSONObject(i)
                .getJSONObject("countItem")
                .getString("value");
        pSlots.replace(Integer.parseInt(completenessPercentageSlot), Integer.parseInt(countItemSlot));
      }
    } catch (JSONException e) {
      System.out.println("Falied to parse JSON pSlot and pCount");
      e.printStackTrace();
    }
    //legacy code below
//    ResultSet prs = getResultSet(populatePercentageSlots(queryParameters));
//    while(prs.hasNext()){
//      qs = prs.next();
//      String completenessPercentageSlot = qs.getLiteral("completenessPercentage").getValue().toString();
//      String countItemSlot = qs.getLiteral("countItem").getValue().toString();
//
//      pSlots.replace(Integer.parseInt(completenessPercentageSlot), Integer.parseInt(countItemSlot));
//    }

    System.out.println("-->pslots: " + pSlots);


    ResultSet ers = null;
    Map<Integer, Map<String,String>> elSlots = new HashMap<>();
    int pCounter = 0;
    Integer currentPercentage=pSlotsNumbers.get(pCounter);//?
    for(String currentEntityQuery : populateEntities(queryParameters)){
      Map<String,String> lSlots = new HashMap<>();
      System.out.println("getting 10 entities for: " + queryParameters);
      responseObj = getQueryResult(currentEntityQuery);
      System.out.println("entity responseObj: " + responseObj);
//      ers = getResultSet(currentEntityQuery);//legacy

      //mabye just make a throws JSONException signature on the method...
      try {
        if(responseObj.getJSONArray("bindings").length()==0){
          System.out.println("there seems to be no label for this entity");
          lSlots.put("","");
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
//      if(ers.hasNext()==false){//legacy
//        System.out.println("there is no result set");
//        lSlots.put("","");
//      }
      try {
        System.out.println("entity json iterable: " + responseObj.getJSONArray("bindings").length());
        for(int i=0;i<responseObj.getJSONArray("bindings").length();i++){
          String currentItem = responseObj.getJSONArray("bindings")
                  .getJSONObject(i)
                  .getJSONObject("item")
                  .getString("value");
          String currentLabel = "";
          //labels are not present in the wolfgang.unibz.it server so get them
          // from  the &wbgetentities wikidata api
          //might have to count the average number of lables and see if that bursts down the server
          if(responseObj.getJSONArray("bindings")
                  .getJSONObject(i).getJSONObject("label") != null){
            currentLabel = responseObj.getJSONArray("bindings")
                    .getJSONObject(i).getJSONObject("label").getString("value");
          } else {
            currentLabel = responseObj.getJSONArray("bindings")
                    .getJSONObject(i).getJSONObject("item").getString("value");
          }
          currentLabel = currentLabel.replace('\'',' '); //clean '-s
          currentLabel = currentLabel.replace(',', ' '); //clean ,-s
          lSlots.put(currentItem,currentLabel);
          }
      } catch (JSONException e) {
        System.out.println("Falied to parse JSON pSlot and pCount");
        e.printStackTrace();
      }
//      while(ers.hasNext()){
//        qs = ers.next();
//        String currentItem = qs.get("item").toString();
//        String currentLabel="";
//        if(qs.getLiteral("label")!=null){
//          currentLabel = qs.getLiteral("label").getValue().toString();
//        } else{
//          currentLabel = qs.get("item").toString();
//        }
//        currentLabel = currentLabel.replace('\'',' '); //clean '-s
//        currentLabel = currentLabel.replace(',', ' '); //clean ,-s
//        lSlots.put(currentItem,currentLabel);
//      }

      currentPercentage=pSlotsNumbers.get(pCounter);
      pCounter++;
      elSlots.put(currentPercentage,lSlots);
    }
    pCounter=0;

    System.out.println("elslots: " + elSlots);

    List<List<Integer>> binaryVec = new ArrayList();
    List<Integer> currentEntityVec = new ArrayList();


    for (Map.Entry<Integer, Map<String,String>> entry : elSlots.entrySet()) {
      Integer key = entry.getKey();
      Map<String,String> currentMap = entry.getValue();
      for(Map.Entry<String,String> entities : currentMap.entrySet()){
        ResultSet brs = getResultSet(binaryVectorColumn(entities.getKey()));
        currentEntityVec = new ArrayList();
        while(brs.hasNext()){
          qs = brs.next();
          for(Attribute currAttr : attrs){
            String currentBool=null;
            if(qs.getLiteral(currAttr.code).getValue().toString() != null){
              currentBool = qs.getLiteral(currAttr.code).getValue().toString();
            }
            if(currentBool.equals("false")){
              currentEntityVec.add(0);
            } else if(currentBool.equals("true")){
              currentEntityVec.add(1);
            }
          }
        }
        binaryVec.add(currentEntityVec);
      }
    }


    int i = 0;
    int k = 0;
    String entityString = "'";
    String labelString = "'";
    String vectorString = "'";
    for(Map<String,String> currentMap : elSlots.values()){
      entityString = "'";
      labelString = "'";
      vectorString = "'";
      sqlCode+= pSlots.get((int)(((float)i/attrs.size())*100))+", "; //pslot code
      for(Map.Entry<String,String> entry : currentMap.entrySet()){
        entityString+=entry.getKey() + ";";
        labelString+=entry.getValue() + ";";
        for(Integer currInt: binaryVec.get(k)){
          vectorString+= currInt;
        }
        vectorString+=";";
        k++;
      }
      entityString=entityString.substring(0,entityString.length()-1);
      entityString+="', ";
      labelString=labelString.substring(0,labelString.length()-1);
      labelString+="', ";
      vectorString=vectorString.substring(0,vectorString.length()-1);
      vectorString+="', ";
      sqlCode+=entityString;
      sqlCode+=labelString;
      sqlCode+=vectorString;
      i++;
    }
    sqlCode=sqlCode.substring(0,sqlCode.length()-2);
    sqlCode+=");";
    System.out.println(sqlCode);
    try {
      ps = connection.prepareStatement(sqlCode);
      ps.executeUpdate();
      ps.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }
    //sqlCode += ""

    //create sql statement
    //execute database sql statement
  }

  public String binaryVectorColumn(String entity){

    String binVecSql = "";
    binVecSql += "SELECT * WHERE { ";
    for(Attribute currAttr : attrs){
      binVecSql += "BIND(EXISTS { <"+entity+"> <http://www.wikidata.org/prop/direct/"+currAttr.code+"> ?node } as ?"+currAttr.code+") ";
    }
    binVecSql += "}";
    System.out.println("Binary vector column? query: " + binVecSql);
    return binVecSql;
  }

  public String populateTotalColumn(List<Value> queryParameters){
    //System.out.println("current queryParams in populateTotalColumn() " + queryParameters);
    String totalSparql = "select (COUNT (distinct ?item) AS ?count) " + "where { " ;
    totalSparql += " ?item <http://www.wikidata.org/prop/direct/P31> <http://www.wikidata.org/entity/"+this.wikidataclass.code+">."; //P31 is instance of
    totalSparql += mainQuery(queryParameters);
    totalSparql += "}";
    //System.out.println("Query: " + totalSparql);
    //addTotalColumnDB(this.connection, queryParameters, getResultSet(totalSparql));
    totalQueries++;
    System.out.println("total column query: " + totalSparql);
    return totalSparql;
  }

  public List<String> populateAttribCount(List<Value> queryParameters){
    String attrSparql = "";
    List<String> attrSparqlQueries = new ArrayList();
    for(Attribute currAttr : attrs) {
      attrSparql = "select (COUNT (distinct ?item) AS ?count) " + "where { ";
      attrSparql += " ?item <http://www.wikidata.org/prop/direct/P31> <http://www.wikidata.org/entity/" + this.wikidataclass.code + ">."; //P31 is instance of
      attrSparql += mainQuery(queryParameters);
      attrSparql += "?item <http://www.wikidata.org/prop/direct/"+currAttr.code+"> ?exists .";
      attrSparql += "}";
      //System.out.println("attrquery: " + attrSparql);
      totalQueries++;
      attrSparqlQueries.add(attrSparql);
      //addAttribColumnDB(this.connection, queryParameters, getResultSet(attrSparql));
    }
    System.out.println("attribute count columns : " + attrSparqlQueries);
    return attrSparqlQueries;
  }

  public String populatePercentageSlots(List<Value> queryParameters){
    String percentageSparql = "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>";
    percentageSparql += "SELECT ?completenessPercentage (COUNT(?item) AS ?countItem){";
    percentageSparql += "SELECT ?item (xsd:integer((((COUNT(DISTINCT(?prop))-1)/"+attrs.size()+"*100)))  AS ?completenessPercentage)";
    percentageSparql += "WHERE { ";
    percentageSparql += " ?item <http://www.wikidata.org/prop/direct/P31> <http://www.wikidata.org/entity/"+this.wikidataclass.code+">."; //P31 is instance of
    percentageSparql += mainQuery(queryParameters);
    String attributeString = "{ ";
    for(Attribute currAttr : attrs){
      attributeString += "{?item <http://www.wikidata.org/prop/direct/"+currAttr.code+"> ?val. BIND (\""+currAttr.code+"\" AS ?prop)} UNION";
    }
    attributeString += "{BIND (\"PDUMMY\" AS ?prop) } }";
    percentageSparql += attributeString;
    percentageSparql += "} GROUP BY ?item}GROUP BY ?completenessPercentage";

    totalQueries++;
    System.out.println("percentage columns Sparql: " + percentageSparql);
    return percentageSparql;
  }

  public List<String> populateEntities(List<Value> queryParameters){
    List<String> entitySparqlQueries = new ArrayList<>();
    for(int i = 0; i<= attrs.size(); i++) {
      String entitySparql = "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>";
      entitySparql += "SELECT distinct ?item ?label (xsd:integer((((COUNT(DISTINCT(?prop))-1)/"+attrs.size()+"*100))) AS ?completenessPercentage) " + "where { ";
      entitySparql += " ?item <http://www.wikidata.org/prop/direct/P31> <http://www.wikidata.org/entity/" + this.wikidataclass.code + ">."; //P31 is instance of
      entitySparql += mainQuery(queryParameters);
      String attributeString = "{ ";
      for (Attribute currAttr : attrs) {
        attributeString += "{?item <http://www.wikidata.org/prop/direct/" + currAttr.code + "> ?val. BIND (\"" + currAttr.code + "\" AS ?prop)} UNION";
      }
      attributeString += "{BIND (\"PDUMMY\" AS ?prop) } }";
      entitySparql += attributeString;
      entitySparql += "OPTIONAL { ?item <http://www.w3.org/2000/01/rdf-schema#label> ?label . FILTER (lang(?label)=\"en\") }";
      entitySparql += "} GROUP BY ?item ?label HAVING(?completenessPercentage = "+(int)(((float)i/attrs.size())*100)+") LIMIT 10";
      //System.out.println("entitySparql for "+i+": " + entitySparql);
      totalQueries++;
      entitySparqlQueries.add(entitySparql);
    }
    System.out.println("entities? sparql: " + entitySparqlQueries);
    return entitySparqlQueries;
  }

  public String mainQuery(List<Value> queryParameters){
    String entitySparql = "";
    for(int j=0;j<queryParameters.size();j++){
      if(!queryParameters.get(j).name.equals("any")&&!queryParameters.get(j).name.equals("other")){
        entitySparql += "?item <http://www.wikidata.org/prop/direct/"+queryParameters.get(j).facet.attr.code+"> "
                +  "<http://www.wikidata.org/entity/"+ (queryParameters.get(j)).code + "> .";
      }
      else if(queryParameters.get(j).name.equals("other")){
        entitySparql += "  FILTER EXISTS { ?item <http://www.wikidata.org/prop/direct/"+queryParameters.get(j).facet.attr.code+"> ?"+queryParameters.get(j).facet.getNameforDB()+" } ";
        for(Value currValue : queryParameters.get(j).facet.values){
          if(!currValue.name.equals("any") && !currValue.name.equals("other"))
            entitySparql +=" FILTER NOT EXISTS { ?item <http://www.wikidata.org/prop/direct/"+queryParameters.get(j).facet.attr.code+"> <http://www.wikidata.org/entity/"+((Value)currValue).code+"> }.";
        }
      }
    }
    totalQueries++;
    return entitySparql;
  }

  public void populateThisRowWithSeveralQueries(List<Value> queryParameters){

      getAllResults(queryParameters);
//    //1. get total nr of instances
//    populateTotalColumn(queryParameters);
//    System.out.println("totalquery should be done now --");
//    //2. get attrib numbers
//    populateAttribCount(queryParameters);
//    System.out.println("attribquery should be done now --");
//    //3.get percentage slots
//    populatePercentageSlots(queryParameters);
//    System.out.println("percentagequery should be done now--");
//    //4. getting entities and labels
//    populateEntities(queryParameters);
//    System.out.println("entityquery should be done now--");

  }

  public void createTable(Connection connection){

    this.connection = connection;
    PreparedStatement ps = null;
    String sqlCode = "create table if not exists " + this.getNameforDB() + " (";

    for(Facet currentFacet : this.facets){
      sqlCode +=currentFacet.attr.code + " varchar, ";
    }
    sqlCode += "total integer, ";
    for(Attribute currentAttribute : this.attrs){
      sqlCode += currentAttribute.code+"Count" + " int, ";
    }

    int attrCount = this.attrs.size();
    for(int i = 0; i <= attrCount; i++){
      sqlCode += "p"+(int)(((float)i/attrs.size())*100)+" integer, ";
      sqlCode += "e"+(int)(((float)i/attrs.size())*100)+" varchar, ";
      sqlCode += "l"+(int)(((float)i/attrs.size())*100)+" varchar, ";
      sqlCode += "bin_vec"+(int)(((float)i/attrs.size())*100)+" varchar, ";
    }
    sqlCode = sqlCode.substring(0, sqlCode.length()-2);
    sqlCode += ");";
    System.out.println("SQLcode: "+sqlCode);

    try {
      ps = connection.prepareStatement(sqlCode);
      ps.executeUpdate();
      ps.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  /**
   * @param values the list of all profiles provided by the client in matrix.json
   * @return the list of ImmutableList to compute the Cartesian product of values
   */
  private static List<ImmutableList<Value>> makeListofImmutable(List<List<Value>> values) {
    List<ImmutableList<Value>> converted = new LinkedList();
    values.forEach(array -> {
      converted.add(ImmutableList.copyOf(array));
    });
    return converted;
  }

  public String getNameforDB(){
    String returnString="";

    if(this.name==""){
      returnString="noname";
    } else{
      returnString = this.name.replace(" ", "");
    }
    return returnString;
  }

  public void insertProfileInformation(Connection con){
    this.connection = con;
    PreparedStatement ps = null;
    String profileCode = "insert into profile(name,description) values(\'"
            +this.getNameforDB()+"\',\'"+this.description+"\');";
    System.out.println("SQLcode for profiletable population: "+profileCode);
    try {
      ps = connection.prepareStatement(profileCode);
      ps.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
    }

    for(Attribute currAttr : this.attrs){
      String attrxprofileString = "insert into attrxprofile(name,attr, attrcode) values('"
              +this.getNameforDB()+"', '";
      attrxprofileString+=currAttr.getNameforDB()+"', " + "'" + currAttr.code+"')";
      System.out.println("About to execute: " + attrxprofileString);
      try {
        ps = connection.prepareStatement(attrxprofileString);
        ps.executeUpdate();
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }



    for(Facet currFacet : this.facets){
      String facetsxprofileString = "insert into facetsxprofile(name,facets,code) values('"
              +this.getNameforDB()+"', '";
      facetsxprofileString +=currFacet.getNameforDB()+"', " + "'" + currFacet.attr.code+"')";
      try {
        ps = connection.prepareStatement(facetsxprofileString);
        ps.executeUpdate();
      } catch (SQLException e) {
        e.printStackTrace();
      }
    }


    String valuesxfacetString = "";
    for(Facet currFacet : this.facets){

      for(Value currValue : currFacet.values){
        valuesxfacetString = "insert into valuesxfacet(name,facet,facetcode,value,valuecode) values('"
                +this.getNameforDB()+"', '"+currFacet.getNameforDB()+"','"+currFacet.attr.code+"','";
        valuesxfacetString += currValue.getNameforDB()+"','"+currValue.code+"')";
        try {
          System.out.println("-->executing " +valuesxfacetString);
          ps = connection.prepareStatement(valuesxfacetString);
          ps.executeUpdate();
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }

    }

  }



  private JSONObject getQueryResult(String query)  {
    System.out.println("Incoming query in new method: " + query);

    String urlString = null;
    try {
      urlString = "http://wolfgang.inf.unibz.it:3030/WD?query="+ URLEncoder.encode(query, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    ;

    URL url = null;
    try {
      url = new URL(urlString);
    } catch (MalformedURLException e) {
      e.printStackTrace();
    }
    HttpURLConnection con = null;
    try {
      con = (HttpURLConnection) url.openConnection();
    } catch (IOException e) {
      e.printStackTrace();
    }

    // By default it is GET request
    try {
      con.setRequestMethod("GET");
    } catch (ProtocolException e) {
      e.printStackTrace();
    }

    //add request header
    con.setRequestProperty("User-Agent", USER_AGENT);

    int responseCode = 0;
    try {
      responseCode = con.getResponseCode();
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println("Sending get request : "+ url);
    System.out.println("Response code : "+ responseCode);

    // Reading response from input Stream
    BufferedReader in = null;
    try {
      in = new BufferedReader(
              new InputStreamReader(con.getInputStream()));
    } catch (IOException e) {
      e.printStackTrace();
    }
    String output;
    StringBuffer response = new StringBuffer();

    try {
      while ((output = in.readLine()) != null) {
        response.append(output);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      in.close();
    } catch (IOException e) {
      e.printStackTrace();
    }

    //printing result from response
    System.out.println("new response: " + response.toString());
    JSONObject jsonObj = null;
    JSONObject responseObj = null;

    try {
      jsonObj = new JSONObject(response.toString());
      responseObj = jsonObj.getJSONObject("results");
//              .getJSONArray("bindings")
//              .getJSONObject(0)
//              .getJSONObject("count")
//              .getString("value");
              //.getString("results");
//              .getJSONArray("results")
//              .getString(0);
      System.out.println("||---->> JSON obj extract: " + responseObj);
    } catch (JSONException e) {
      e.printStackTrace();
    }
    return responseObj;
  }


  //------------------deprecated----------------------------//
  public void getEntityBinVec(List<String> entities){

  }
  public void addTotalColumnDB(Connection connection, List<Value> queryParameters, ResultSet rs){
    PreparedStatement ps = null;
    String sqlCode = "insert into "+this.getNameforDB()+" (";
    for (Facet currentFacet : this.facets){
      sqlCode += currentFacet.attr.code +", ";
    }
    sqlCode += " total) ";
    sqlCode += "values(";
    for(Value currentValue : queryParameters){
      sqlCode += "'"+currentValue.code+"'"+", ";
    }
    sqlCode += rs.next().get("count").asLiteral().getValue().toString() + ");";
    System.out.println("//////sql total count code: " + sqlCode);
    try {
      ps = connection.prepareStatement(sqlCode);
      ps.executeUpdate();
      ps.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }
  public void addAttribColumnDB(Connection connection, List<Value> queryParameters, ResultSet rs){
    PreparedStatement ps = null;
    String sqlCode = "insert into "+this.getNameforDB()+" (";
    for(Attribute currentAttribute : this.attrs){
      sqlCode += currentAttribute.code+"count, ";
    }
  }
}
