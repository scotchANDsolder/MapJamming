PFont font;
String time = "900";
int t;
int interval = 900;

void setup()
{
  fullScreen();
  font = createFont("Ubuntu bold", 78);
  //print(PFont.list());
  background(255);
  fill(0);
}

void draw()
{
    background(0);
    fill(255,0,0);   
    t = interval-int(millis()/1000);
    time = nf(t , 3);
    if(t == 0){
      println("GAME OVER");
    interval+=10;}
        textAlign(CENTER);
  textSize(200);
   text(time, width/2, height/2);
}
