# -*- coding: utf-8 -*-
"""把 render-excel.py 渲染的 PNG 接成交互 HTML（底图 = 我验过的 PNG，路线叠在透明画布上）。
像素变换与 render-excel.py 完全一致：x = 190+(col-1)*13（col = 37.5+2*lat） ⇒ x = 664.5+26*lat
                                      y = 66+(row-1)*13（row = 53.5-2*depth） ⇒ y = 748.5-26*depth
"""
import base64, io, json, os
ROWS=['B','C','D','E','F','G','H','K','L','M','N','P','Q','R']
PAIRS=[('B','C'),('D','E'),('F','G'),('H','K'),('L','M'),('N','P'),('Q','R')]
BAND_D0=[0,3,6,9,12,15,18]
CORR=[(0,-2,0)]+[(k,BAND_D0[k-1]+1,BAND_D0[k]) for k in range(1,7)]+[(7,BAND_D0[6]+1,BAND_D0[6]+3)]
J_LAT={1:-5,2:-4,3:-3.5,4:-2.5,5:-2,6:-1}
SD={1:24.25,2:21.75,3:21.25}
AZ=[('a7',-17.25,-2.5),('a8',-11.25,-2.5),('a9',-8.25,-2.5),
    ('a4',-11.25,-3.0),('a5',-8.25,-3.0),('a6',-17.25,-5.5),
    ('a3',-15.75,-8.5),('a2',-12.75,-8.5),('a1',-9.75,-8.5)]
PNG=r'E:\claude\parcel-SPU\.devtools\floorplan-excel.png'
b64=base64.b64encode(io.open(PNG,'rb').read()).decode('ascii')
geo=dict(rows=ROWS,pairs=PAIRS,bandD0=BAND_D0,corr=CORR,shelfW=3,shelves=12,left=4,spineW=3,
         J=J_LAT,JD=[22.75,24.75],SD=SD,SL=[3,24],YL=30.5,YD=25.5,A=AZ,
         IG=(-6,-3,-2.6),EX=(-15.5,3,18),
         minLat=0,maxLat=1,minD=0,maxD=0,P=26,LL=664.5,T=748.5,imgW=2154,imgH=1043)
tpl=io.open(r'E:\claude\parcel-SPU\.devtools\floorplan-template.html',encoding='utf-8').read()
out=r'E:\claude\parcel-SPU\docs\floorplan.html'
io.open(out,'w',encoding='utf-8',newline='').write(tpl.replace('__B64__',b64).replace('__GEO__',json.dumps(geo,ensure_ascii=False)))
print('HTML:',out,os.path.getsize(out),'字节')
