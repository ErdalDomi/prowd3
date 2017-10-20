package it.unibz.inf;

import java.util.List;

public class Facet {
  Attribute attr;
  String name;
  List<Value> values;

  public Facet(Attribute attr, String name){
    this.name = name;
    this.attr = attr;
  }

  public void assignValues(List<Value> values){
    this.values = values;
  }

  public String toString(){
    return attr + " " + name;

  }

  public String getNameforDB(){
    String returnString="";

    if(this.name==""){
      returnString=this.attr.code;
    } else{
      returnString = this.name.replace(" ", "");
    }
    return returnString;
  }

}
