PFont font;
String time = "900";
int t;
int interval = 900;
PImage img;

int flag = 0


void setup()
{
 fullScreen(2);
  font = createFont("Ubuntu bold", 78);
  //print(PFont.list());
  background(255);
  img = loadImage("GPSicon.jpg");

}

void draw()
{
    background(0);
    fill(255,0,0);   
    imageMode(CENTER);
      image(img, width/2, height/3,500,500);
      if(keyPressed & keyCode == UP){
        flag = 1;
      }
    t = interval-int(millis()/1000);
    time = nf(t , 3);
    if(t == 0){
      println("GAME OVER");
    interval+=10;}
    textAlign(CENTER);
  textSize(200);
   text(time, width/2,height*5/6);
   
   if(t < 1){
   exit();
   }
      
}
