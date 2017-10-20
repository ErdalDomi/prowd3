package it.unibz.inf;

public class Value {
  String name="";
  String code;
  Facet facet;

  public Value(String code, String name){
    this.code=code;
    this.name = name;
  }

  public String toString(){
    return this.name + " " + this.code;
  }

  public void setFacet(Facet facet){
    this.facet = facet;
  }

  public String getNameforDB(){
    String returnString="";

    if(this.name==""){
      returnString=this.code;
    } else{
      returnString = this.name.replace(" ", "");
    }
    return returnString;
  }
}
