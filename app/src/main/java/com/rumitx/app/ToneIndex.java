package com.rumitx.app;
/** User-supplied DMR007 chart. Index 0 is None; DCS 1..82, not 0..82 codes. */
public final class ToneIndex {
 private ToneIndex(){}
 public static final double[] CTCSS={67.0,69.3,71.9,74.4,77.0,79.7,82.5,85.4,88.5,91.5,94.8,97.4,100.0,103.5,107.2,110.9,114.8,118.8,123.0,127.3,131.8,136.5,141.3,146.2,151.4,156.7,159.8,162.2,165.5,167.9,171.3,173.8,177.3,179.9,183.5,186.2,189.9,192.8,196.6,199.5,203.5,206.5,210.7,218.1,225.7,229.1,233.6,241.8,250.3,254.1};
 public static final String[] DCS={"023","025","026","031","032","036","043","047","051","053","054","065","071","072","073","074","114","115","116","122","125","127","131","132","134","143","145","152","155","156","162","165","172","174","205","223","225","226","243","244","245","246","251","252","255","261","263","265","266","271","274","306","311","315","325","331","332","343","346","351","356","364","365","371","411","412","413","423","431","432","445","446","452","454","455","462","464","465","466","503","506","516"};
 public static int ctcss(double hz){if(hz==0)return 0;for(int i=0;i<CTCSS.length;i++)if(Math.abs(hz-CTCSS[i])<0.0001)return i+1;throw new IllegalArgumentException("Tone-to-module-index mapping is not verified for "+hz+" Hz");}
 public static String normalize(String code){if(code==null)return "";String c=code.trim().toUpperCase(java.util.Locale.US);if(c.startsWith("D"))c=c.substring(1);if(c.endsWith("N")||c.endsWith("I"))c=c.substring(0,c.length()-1);if(c.isEmpty()||c.equals("0"))return "";try{return String.format(java.util.Locale.US,"%03d",Integer.parseInt(c));}catch(NumberFormatException e){return c;}}
 public static int dcs(String code){String c=normalize(code);if(c.isEmpty())return 0;for(int i=0;i<DCS.length;i++)if(c.equals(DCS[i]))return i+1;throw new IllegalArgumentException("Tone-to-module-index mapping is not verified for DCS "+code);}
 public static final class Settings {
  public final int txType,rxType,tx,rx;
  Settings(int txType,int rxType,int tx,int rx){this.txType=txType;this.rxType=rxType;this.tx=tx;this.rx=rx;}
 }
 /** S80 firmware ver0.5.1: 1=carrier, 2=CTCSS, 3=DCS, 4=inverse DCS.
  * Mode0 skips carrier squelch processing; keep directions independent. */
 public static Settings resolve(double txHz,double rxHz,String txDcs,String rxDcs){
  boolean a=!normalize(txDcs).isEmpty(),b=!normalize(rxDcs).isEmpty();
  if((a||b)&&(txHz!=0||rxHz!=0))
   throw new IllegalArgumentException("Mixed CTCSS/DCS tone types are not supported by this module");
  if(a||b){
   boolean txInv=txDcs!=null&&txDcs.toUpperCase(java.util.Locale.US).endsWith("I");
   boolean rxInv=rxDcs!=null&&rxDcs.toUpperCase(java.util.Locale.US).endsWith("I");
   if(a&&b&&txInv!=rxInv)throw new IllegalArgumentException("TX/RX DCS polarity must match");
   int tx=dcs(txDcs),rx=dcs(rxDcs);
   return new Settings(tx==0?1:(txInv?4:3),rx==0?1:(rxInv?4:3),tx,rx);
  }
  int tx=ctcss(txHz),rx=ctcss(rxHz);
  return new Settings(tx==0?1:2,rx==0?1:2,tx,rx);
 }
}
