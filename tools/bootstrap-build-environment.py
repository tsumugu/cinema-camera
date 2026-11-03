#!/usr/bin/env python3
"""Prepare a local Android SDK from Google's signed-by-TLS, checksum-verified archives."""
from pathlib import Path
import concurrent.futures
import hashlib
import os
import shutil
import stat
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

root=Path(__file__).resolve().parents[1]
tool=root/'.toolchain'
sdk=tool/'android-sdk'
tool.mkdir(exist_ok=True)
sdk.mkdir(exist_ok=True)
repository=ET.parse(root/'build/android-repository.xml').getroot()
packages={}
for package in repository:
    if not package.tag.endswith('remotePackage'): continue
    name=package.get('path')
    if name not in ('platforms;android-36','build-tools;35.0.0','platform-tools'): continue
    if name=='platforms;android-36' and package.findtext('display-name')!='Android SDK Platform 36': continue
    for archive in package.findall('archives/archive'):
        if archive.findtext('host-os') not in (None,'linux'): continue
        complete=archive.find('complete')
        packages[name]=(complete.findtext('url'),complete.findtext('checksum'))
        break

def install(item):
    name,(url,checksum)=item
    target=sdk.joinpath(*name.split(';'))
    if (target/'source.properties').exists():
        print('Already installed:',name,flush=True)
        return
    archive=tool/Path(url).name
    if not archive.exists() or hashlib.sha1(archive.read_bytes()).hexdigest()!=checksum:
        print('Downloading:',name,flush=True)
        with urllib.request.urlopen('https://dl.google.com/android/repository/'+url,timeout=60) as stream:
            with archive.open('wb') as out:
                shutil.copyfileobj(stream,out)
    if hashlib.sha1(archive.read_bytes()).hexdigest()!=checksum: raise ValueError('SDK checksum mismatch: '+name)
    target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as contents:
        prefixes={p.filename.split('/')[0] for p in contents.infolist()}
        if len(prefixes)!=1: raise ValueError('Unexpected SDK archive layout')
        prefix=next(iter(prefixes))+'/'
        for entry in contents.infolist():
            relative=Path(entry.filename.removeprefix(prefix))
            if relative.is_absolute() or '..' in relative.parts: raise ValueError('Unsafe SDK archive path')
            dest=target/relative
            if entry.is_dir(): dest.mkdir(parents=True,exist_ok=True); continue
            dest.parent.mkdir(parents=True,exist_ok=True)
            with contents.open(entry) as source,dest.open('wb') as output: shutil.copyfileobj(source,output)
            mode=(entry.external_attr>>16)&0o777
            if mode: dest.chmod(mode)
    print('Installed:',name,flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(install,packages.items()))

# This system Java includes the compiler/jlink modules but omits their bin
# launchers. A project-local copy with module launchers is a complete build JDK.
jdk=tool/'jdk'
if not jdk.exists(): shutil.copytree('/usr/lib/jvm/java-21-openjdk-amd64',jdk,symlinks=True)
for executable,module in {
    'javac':'jdk.compiler/com.sun.tools.javac.Main',
    'jlink':'jdk.jlink/jdk.tools.jlink.internal.Main',
    'jmod':'jdk.jlink/jdk.tools.jmod.Main',
    'jar':'jdk.jartool/sun.tools.jar.Main',
    'javadoc':'jdk.javadoc/jdk.javadoc.internal.tool.Main',
    'jarsigner':'jdk.jartool/sun.security.tools.jarsigner.Main',
}.items():
    launcher=jdk/'bin'/executable
    launcher.write_text('#!/bin/sh\nexec "$(dirname "$0")/java" --module '+module+' "$@"\n')
    launcher.chmod(0o755)

(root/'local.properties').write_text('sdk.dir='+str(sdk)+'\n')
gradle_home=tool/'gradle-home'
gradle_home.mkdir(exist_ok=True)
proxy=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
properties=''
if proxy.hostname:
    for scheme in ('http','https'):
        properties+=f'systemProp.{scheme}.proxyHost={proxy.hostname}\n'
        properties+=f'systemProp.{scheme}.proxyPort={proxy.port or 8080}\n'
properties+='org.gradle.java.installations.paths='+str(jdk)+'\n'
properties+='org.gradle.java.installations.auto-detect=false\n'
properties+='org.gradle.java.installations.auto-download=false\n'
(gradle_home/'gradle.properties').write_text(properties)
print('Build environment ready',flush=True)
