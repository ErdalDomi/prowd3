package it.unibz.inf;

import com.sun.jersey.api.client.ClientResponse;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.json.JSONException;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Path("/start")
public class Index {
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getMessage(@Context UriInfo info) throws ServletException, IOException {

    String profileName = "";
    String profileDescription = "";
    String profileClassName = "";
    WikidataClass profileClass = null;
    List<Attribute> profileAttributes = new ArrayList<Attribute>();
    List<Facet> profileFacets = new ArrayList<Facet>();
    List<List<Value>> profileValues = new ArrayList<List<Value>>();

    MultivaluedMap<String, String> parameters = info.getQueryParameters();

    profileName = parameters.getFirst("profile-name");
    profileDescription = parameters.getFirst("profile-description");

    profileClass = new WikidataClass(parameters.getFirst("profile-class"));
    profileClassName = getQlabel(profileClass.code);
    profileClass.setName(profileClassName.substring(0,profileClassName.length()-3));


    //The code below will get the attributes from the user parameters and find the name from the code
    //It will also count the number of facets given by the user, so in the next iteration
    //we can find their values accordingly
    //version 4.0 of the system should have automatic suggestions from code to name
    //version 5.0 should have from name to code
    int totalFacets = 0;
    Object[] keyList = parameters.keySet().toArray();
    for(Object key : keyList){
      if(key.toString().contains("attribute")){
        String currAttributeCode = parameters.getFirst(key.toString());
        Attribute currAttribute = new Attribute(currAttributeCode);
        currAttribute.setName(getPropertyName(currAttributeCode));
        profileAttributes.add(currAttribute);
      }
      if(key.toString().contains("facet")){
        totalFacets++;
      }
    }


    //this is the next iteration over the keys, here we extract information about the facets
    //every for loop we add one Facet to the profileFacets list
    //and one Value list to the profileValues
    //this way we give the facets their attribute and make the lists ready to populate
    //for every facet, instantiate object with attribute code
    //html has 1,2,3 whlie i has 0,1,2 hence the i+1
    for(int i = 0; i<totalFacets; i++){
      String currentFacetCode = parameters.getFirst("profile-facet"+(i+1));
      String currentFacetName = getPropertyName(currentFacetCode);
      profileFacets.add(new Facet(new Attribute(currentFacetCode), currentFacetName));
      profileValues.add(new ArrayList());
    }

    //here we iterate over each facet and for every facet we have to populate it with an arbitrary number of values
    //therefore the structure of this bit of code is a for inside a for
    for(int i=0; i<totalFacets;i++){ //we iterate the request parameters for each facet
      for(Object key : keyList){
        if(key.toString().contains("f"+(i+1)+"v") && key.toString().length() > 10){ //why > 10?
          //if its a facet value input field for the current facet
          //get the right faceet value list
          //instantiate a normal value from the parameter
          //insert it into list
          String currValue = parameters.getFirst(key.toString());
          String currValueName = getQlabel(currValue);
          currValueName = currValueName.substring(0,currValueName.length()-3);
          Value valueToAdd = new Value(currValue, currValueName);
          valueToAdd.setFacet(profileFacets.get(i)); //this links the value to its corresponding facet
          profileValues.get(i).add(valueToAdd);
        }
      }
      //we got the values from the input, now we need to add any and other
      Value anyValue = new Value("any","any");
      anyValue.setFacet(profileFacets.get(i));
      Value otherValue = new Value("other", "other");
      otherValue.setFacet(profileFacets.get(i));
      profileValues.get(i).add(anyValue);
      profileValues.get(i).add(otherValue);
      //and finally assign each value list to its corresponding facet
      profileFacets.get(i).assignValues(profileValues.get(i));
    }

    Profile profile1 = new Profile(profileName, profileDescription,
            profileClass,profileAttributes,profileFacets);
    System.out.println(profile1);
    return Response.status(ClientResponse.Status.ACCEPTED).build();
  }

  public String getQlabel(String code){
    String returnValue = "";
    String endpoint = "https://query.wikidata.org/sparql";
    QueryExecution qe;
    ResultSet rs;
    QuerySolution qs;
    String query = "";
    query = "SELECT DISTINCT * WHERE {\n" +
            "  <http://www.wikidata.org/entity/"+code+"> <http://www.w3.org/2000/01/rdf-schema#label> ?label . \n" +
            "  FILTER (langMatches( lang(?label), \"EN\" ) )  \n" +
            "}";
    qe = QueryExecutionFactory.sparqlService(endpoint, query);//fire up query
    rs = qe.execSelect();
    qs = rs.next();
    returnValue = qs.getLiteral("label").toString();

    return returnValue;
  }

  //to get the P-code labels we have to use a wikidata api endpoint
  //first we send a GET reqeuest to get a json file with the info we need
  //then we use a JSON library to get the value we want

  //example link: https://www.wikidata.org/w/api.php?action=wbgetentities&props=labels&ids=Q76&languages=en
  public String getPropertyName(String code) throws ServletException, IOException {

    String returnValue = "";
    URL url = new URL("https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+code+"&languages=en&format=json");
    HttpURLConnection con = (HttpURLConnection) url.openConnection();
    con.setRequestMethod("GET");
    con.setRequestProperty("Content-Type", "application/json");
    int status = con.getResponseCode();
    BufferedReader in = new BufferedReader(
            new InputStreamReader(con.getInputStream()));
    String inputLine;
    StringBuffer content = new StringBuffer();
    while ((inputLine = in.readLine()) != null) {
      content.append(inputLine);
    }

    JSONObject jsonObj = null;

    try {
      jsonObj = new JSONObject(content.toString());
      returnValue = jsonObj.getJSONObject("entities")
              .getJSONObject(code)
              .getJSONObject("labels")
              .getJSONObject("en")
              .getString("value");

    } catch (JSONException e) {
      System.out.println("label not found for " + code);
      System.out.println("returning code");
      returnValue = code;
    }

    in.close();
    con.disconnect();

    return returnValue;
  }
}
