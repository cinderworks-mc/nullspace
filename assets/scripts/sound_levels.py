import json,sys,os,glob,numpy as np,soundfile as sf
# Vanilla asset index/objects as cached by NeoGradle (override with NS_MC_ASSETS)
A=os.environ.get("NS_MC_ASSETS",os.path.join(os.environ.get("GRADLE_USER_HOME",os.path.expanduser("~/.gradle")),"caches/neoformruntime/assets"))
idx=json.load(open(A+"/indexes/17.json"))["objects"]
def stats(x):
    if x.ndim>1:x=x.mean(1)
    pk=np.abs(x).max(); rms=np.sqrt((x**2).mean())
    # active RMS: over samples above -50 dB of peak
    m=np.abs(x)>pk*10**(-50/20); arms=np.sqrt((x[m]**2).mean())
    db=lambda v:20*np.log10(v+1e-12)
    return len(x),pk,db(pk),db(rms),db(arms)
print("%-48s %6s %7s %7s %8s"%("file","dur","peak","rms","rms-act"))
for f in sorted(glob.glob("src/main/resources/assets/nullspace/sounds/*.ogg")):
    x,sr=sf.read(f);n,pk,dpk,drms,darms=stats(x);print("%-48s %5.2fs %7.1f %7.1f %8.1f"%(os.path.basename(f),n/sr,dpk,drms,darms))
for k in ["ui/button/click","note/chime","random/orb","block/amethyst_block/chime1","block/beacon/activate","block/note_block/bell","block/wooden_door/open1","block/enchantment_table/","random/click","block/barrel/open1","random/pop","block/chest/open","block/respawn_anchor/charge","entity/experience_orb/pickup","ui/toast/in","ui/toast/challenge_complete","block/bell/use","block/lever/click","block/respawn_anchor/set_spawn1","block/end_portal/","block/portal/trigger"]:
    ks=[n for n in idx if n.startswith("minecraft/sounds/"+k) or n.startswith("minecraft/sounds/"+k.replace("/","/"))]
    for n in ks[:2]:
        p=A+"/objects/"+idx[n]["hash"][:2]+"/"+idx[n]["hash"]
        try:
            x,sr=sf.read(p);n_,pk,dpk,drms,darms=stats(x);print("%-48s %5.2fs %7.1f %7.1f %8.1f"%("vanilla "+n[17:-4],n_/sr,dpk,drms,darms))
        except Exception as e: print(n,e)
