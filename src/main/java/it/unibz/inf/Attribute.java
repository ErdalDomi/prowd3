package it.unibz.inf;

public class Attribute {
  String name = "";
  String code;

  public Attribute(String code){
    this.code = code;
  }

  public String toString(){
    return this.name + " " + this.code + " ";
  }

  public void setName(String name){
    this.name = name;
  }

  public String getNameforDB(){

    String returnString="";

    if(this.name==""){
      returnString=this.code;
    } else{
      returnString = this.name.replace(" ", "");
      System.out.print("Attr name changed into: " + returnString);
    }

    return returnString;
  }
}
