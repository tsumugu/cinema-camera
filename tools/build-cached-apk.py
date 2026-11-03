#!/usr/bin/env python3
"""Offline SDK build using dependencies/resources from the preceding Gradle build."""
from pathlib import Path
import os,subprocess,zipfile,shutil,re,copy
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
os.chdir(root)
work=root/'build/cached-apk'
work.mkdir(parents=True,exist_ok=True)
java=root/'.toolchain/jdk/bin/java'
sdk=root/'.toolchain/android-sdk'
bt=sdk/'build-tools/35.0.0'
android=sdk/'platforms/android-36/android.jar'
env=dict(os.environ,JAVA_HOME=str(root/'.toolchain/jdk'))

def run(args):
    print('Building:',Path(str(args[0])).name,flush=True)
    subprocess.run([str(x) for x in args],env=env,check=True)

cache=root/'.toolchain/gradle-home/caches'
libs=list(cache.glob('8.13/transforms/*/transformed/*/jars/classes.jar'))
libs+=list(cache.glob('8.13/transforms/*/transformed/*-api.jar'))
for name in ['androidx.annotation/annotation-jvm','com.google.guava/listenablefuture','org.jetbrains.kotlin/kotlin-stdlib','org.jetbrains/annotations']:
    libs+=list(cache.glob('modules-2/files-2.1/'+name+'/*/*/*.jar'))
libs+=list(cache.glob('modules-2/files-2.1/androidx.lifecycle/*/*/*/*.jar'))
libs+=list(cache.glob('modules-2/files-2.1/androidx.arch.core/*/*/*/*.jar'))
rjar=root/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
classes=work/'classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()
run([java,'com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-proc:none',
     '-classpath',os.pathsep.join(map(str,[android,rjar]+libs)),'-d',classes,
     *sorted((root/'app/src/main/java').rglob('*.java'))])
appjar=work/'app.jar'
with zipfile.ZipFile(appjar,'w') as z:
    for f in classes.rglob('*.class'): z.write(f,f.relative_to(classes))
dex=work/'dex'
dex.mkdir(exist_ok=True)
for f in dex.glob('*.dex'): f.unlink()
args=[java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--debug','--min-api','29','--lib',android,'--output',dex]
for lib in libs: args+=['--classpath',lib]
args += [appjar,rjar,*sorted((root/'app/build/intermediates/dex/debug/mergeExtDexDebug').glob('*.dex'))]
run(args)
# Reuse merged library declarations, then merge current application components
# and permissions so new background services are included in offline builds.
android_ns='http://schemas.android.com/apk/res/android'
ET.register_namespace('android',android_ns)
name_attr='{'+android_ns+'}name'
manifest_tree=ET.parse(root/'app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml')
manifest_root=manifest_tree.getroot()
source=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
for permission in source.findall('uses-permission'):
    if not any(p.get(name_attr)==permission.get(name_attr) for p in manifest_root.findall('uses-permission')):
        manifest_root.insert(0,copy.deepcopy(permission))
merged_app=manifest_root.find('application'); source_app=source.find('application')
merged_app.attrib.update(source_app.attrib)
package=manifest_root.get('package')
def canonical(name):
    return package+name if name and name.startswith('.') else name
for component in source_app:
    match=next((item for item in merged_app if item.tag==component.tag and canonical(item.get(name_attr))==canonical(component.get(name_attr))),None)
    if match is None: merged_app.append(copy.deepcopy(component))
    else: match.attrib.update(component.attrib)
manifest=ET.tostring(manifest_root,encoding='unicode')
gradle=(root/'app/build.gradle').read_text()
version_code=re.search(r'versionCode\s+(\d+)',gradle).group(1)
version_name=re.search(r"versionName\s+'([^']+)'",gradle).group(1)
manifest=re.sub(r'android:versionCode="[^"]+"','android:versionCode="'+version_code+'"',manifest)
manifest=re.sub(r'android:versionName="[^"]+"','android:versionName="'+version_name+'"',manifest)
(work/'AndroidManifest.xml').write_text(manifest)
resources=work/'resources.ap_'
run([bt/'aapt2','link','-I',android,'--manifest',work/'AndroidManifest.xml','--stable-ids',
     root/'app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt',
     '--auto-add-overlay','-o',resources,*sorted(cache.glob('8.13/transforms/*/transformed/*/*.flat')), *sorted((root/'app/build/intermediates/merged_res/debug/mergeDebugResources').glob('*.flat'))])
unsigned=work/'unsigned.apk'
base=root/'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(unsigned,'w') as out:
    with zipfile.ZipFile(base) as old:
        for info in old.infolist():
            n=info.filename
            if n=='AndroidManifest.xml' or n=='resources.arsc' or n.startswith(('res/','assets/')) or n.endswith('.dex'): continue
            if n.startswith('META-INF/') and (n.endswith(('.SF','.RSA','.DSA','.EC')) or n=='META-INF/MANIFEST.MF'): continue
            out.writestr(info,old.read(n))
    with zipfile.ZipFile(resources) as res:
        for info in res.infolist(): out.writestr(info,res.read(info.filename))
    for f in sorted((root/'app/src/main/assets').rglob('*')):
        if f.is_file(): out.write(f,'assets/'+str(f.relative_to(root/'app/src/main/assets')),compress_type=zipfile.ZIP_DEFLATED)
    for f in sorted(dex.glob('*.dex')): out.write(f,f.name,compress_type=zipfile.ZIP_DEFLATED)
aligned=work/'aligned.apk'
run([bt/'zipalign','-P','16','-f','4',unsigned,aligned])
output=root.parent/('cinema-camera-android15-v'+version_name+'-debug.apk')
run([java,'-jar',bt/'lib/apksigner.jar','sign','--ks',root/'.toolchain/debug.keystore','--ks-pass','pass:android',
     '--key-pass','pass:android','--ks-key-alias','AndroidDebugKey','--out',output,aligned])
print('APK:',output,'bytes:',output.stat().st_size,flush=True)
