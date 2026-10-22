import sys, struct, zipfile, hashlib, json, re
from pathlib import Path

def scan(path):
    z=zipfile.ZipFile(path); classes={}; counts={}
    for n in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', n): continue
        b=z.read(n); u=lambda p:struct.unpack_from('<I',b,p)[0]
        def leb(p):
            v=s=0
            while True:
                x=b[p];p+=1;v|=(x&127)<<s;s+=7
                if x<128:return v,p
        def string(p):
            _,p=leb(p);return b[p:b.index(0,p)].decode('utf-8','replace')
        ns,os=struct.unpack_from('<II',b,56);ss=[string(u(os+4*i)) for i in range(ns)]
        nt,ot=struct.unpack_from('<II',b,64);ts=[ss[u(ot+4*i)] for i in range(nt)]
        np,op=struct.unpack_from('<II',b,72);ps=[]
        for i in range(np):
            _,ret,args=struct.unpack_from('<III',b,op+12*i)
            a=[] if not args else [ts[struct.unpack_from('<H',b,args+4+2*j)[0]] for j in range(u(args))]
            ps.append('('+''.join(a)+')'+ts[ret])
        nm,om=struct.unpack_from('<II',b,88);ms=[]
        for i in range(nm):
            cl,pr,name=struct.unpack_from('<HHI',b,om+8*i);ms.append((ts[cl],ss[name]+ps[pr]))
        nf,of=struct.unpack_from('<II',b,80);fs=[]
        for i in range(nf):
            cl,ty,name=struct.unpack_from('<HHI',b,of+8*i);fs.append(ss[name]+':'+ts[ty])
        nc,oc=struct.unpack_from('<II',b,96);counts[n]=nc
        for i in range(nc):
            p=oc+32*i;cl=u(p);sup=u(p+8);data=u(p+24)
            name=ts[cl][1:-1].replace('/','.'); methods=[]; fields=[]
            if data:
                sf,data=leb(data); inf,data=leb(data);dm,data=leb(data);vm,data=leb(data)
                for count in (sf,inf):
                    idx=0
                    for _ in range(count):
                        diff,data=leb(data);idx+=diff;access,data=leb(data);fields.append(fs[idx])
                for count in (dm,vm):
                    idx=0
                    for _ in range(count):
                        d,data=leb(data);idx+=d;access,data=leb(data);code,data=leb(data)
                        methods.append(ms[idx][1])
            classes[name]={'dex':n,'super':ts[sup] if sup!=0xffffffff else None,'methods':methods,'fields':fields}
    return {'file':str(path),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest(),'dex':counts,'resourceCount':sum(n.startswith('res/') for n in z.namelist()),'classes':classes}

if __name__=='__main__':
    out=Path('work/inspection');out.mkdir(parents=True,exist_ok=True)
    for p in sys.argv[1:]:
        d=scan(p);name=Path(p).name.split('-')[0];(out/(name+'.json')).write_text(json.dumps(d,ensure_ascii=False),encoding='utf-8')
        print(name,d['sha256'],'dex',d['dex'],'classes',len(d['classes']),'resources',d['resourceCount'])
