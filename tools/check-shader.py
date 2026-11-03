#!/usr/bin/env python3
"""Run the actual Android GLSL files on a local surfaceless EGL/GLES context.

Synthetic ramps and color patches only. No browser, network or Android SDK needed.
The desktop Mesa results do not replace Android/CameraX device testing.
"""
import ctypes as C
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
E = C.CDLL('libEGL.so.1')
I, U, F, P = C.c_int, C.c_uint, C.c_float, C.c_void_p
E.eglGetProcAddress.argtypes = [C.c_char_p]
E.eglGetProcAddress.restype = P


def egl(name, result, *args):
    fn = getattr(E, name)
    fn.restype, fn.argtypes = result, list(args)
    return fn


def gl(name, result, *args):
    pointer = E.eglGetProcAddress(name.encode())
    if not pointer:
        raise RuntimeError('Missing GLES function ' + name)
    return C.CFUNCTYPE(result, *args)(pointer)


get_display = C.CFUNCTYPE(P, U, P, C.POINTER(I))(E.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
display = get_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
major, minor = I(), I()
assert egl('eglInitialize', U, P, C.POINTER(I), C.POINTER(I))(display, C.byref(major), C.byref(minor))
assert egl('eglBindAPI', U, U)(0x30A0)
attributes = (I * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
config, count = P(), I()
assert egl('eglChooseConfig', U, P, C.POINTER(I), C.POINTER(P), I, C.POINTER(I))(
    display, attributes, C.byref(config), 1, C.byref(count)) and count.value
surface_attrs = (I * 5)(0x3057, 1024, 0x3056, 256, 0x3038)
surface = egl('eglCreatePbufferSurface', P, P, P, C.POINTER(I))(display, config, surface_attrs)
context_attrs = (I * 3)(0x3098, 2, 0x3038)
context = egl('eglCreateContext', P, P, P, P, C.POINTER(I))(display, config, None, context_attrs)
assert surface and context
assert egl('eglMakeCurrent', U, P, P, P, P)(display, surface, surface, context)

create_shader = gl('glCreateShader', U, U)
shader_source = gl('glShaderSource', None, U, I, C.POINTER(C.c_char_p), C.POINTER(I))
compile_shader = gl('glCompileShader', None, U)
shader_iv = gl('glGetShaderiv', None, U, U, C.POINTER(I))
shader_log = gl('glGetShaderInfoLog', None, U, I, C.POINTER(I), P)


def compile_source(name, kind):
    shader = create_shader(kind)
    source = C.c_char_p((ROOT / 'app/src/main/assets' / name).read_bytes())
    shader_source(shader, 1, C.byref(source), None)
    compile_shader(shader)
    ok = I()
    shader_iv(shader, 0x8B81, C.byref(ok))
    if not ok.value:
        message = C.create_string_buffer(4096)
        shader_log(shader, 4096, None, message)
        raise AssertionError(message.value.decode())
    return shader


program = gl('glCreateProgram', U)()
attach = gl('glAttachShader', None, U, U)
attach(program, compile_source('look.vert', 0x8B31))
attach(program, compile_source('look.frag', 0x8B30))
gl('glLinkProgram', None, U)(program)
ok = I()
gl('glGetProgramiv', None, U, U, C.POINTER(I))(program, 0x8B82, C.byref(ok))
assert ok.value, 'shader link failed'
gl('glUseProgram', None, U)(program)
location = gl('glGetUniformLocation', I, U, C.c_char_p)
uniform1i = gl('glUniform1i', None, I, I)
uniform1f = gl('glUniform1f', None, I, F)
uniform2f = gl('glUniform2f', None, I, F, F)
active_texture = gl('glActiveTexture', None, U)
bind_texture = gl('glBindTexture', None, U, U)
tex_image = gl('glTexImage2D', None, U, I, I, I, I, I, U, U, P)
tex_parameter = gl('glTexParameteri', None, U, U, I)
vertices = (F * 8)(-1, -1, 1, -1, -1, 1, 1, 1)
attribute = gl('glGetAttribLocation', I, U, C.c_char_p)(program, b'aPosition')
gl('glEnableVertexAttribArray', None, U)(attribute)
gl('glVertexAttribPointer', None, U, I, U, C.c_ubyte, I, P)(attribute, 2, 0x1406, 0, 0, vertices)
textures = (U * 2)()
gl('glGenTextures', None, I, C.POINTER(U))(2, textures)
uniform1i(location(program, b'uImage'), 0)
uniform1i(location(program, b'uGrain'), 1)


def upload(unit, data, width, height, wrap, floating=False):
    active_texture(0x84C0 + unit)
    bind_texture(0x0DE1, textures[unit])
    for key in (0x2801, 0x2800):
        tex_parameter(0x0DE1, key, 0x2600 if floating else 0x2601)
    for key in (0x2802, 0x2803):
        tex_parameter(0x0DE1, key, wrap)
    buffer = (F * len(data))(*data) if floating else (C.c_ubyte * len(data)).from_buffer_copy(data)
    tex_image(0x0DE1, 0, 0x1908, width, height, 0, 0x1908, 0x1406 if floating else 0x1401, buffer)


neutral_grain = bytes([128, 128, 128, 255]) * (256 * 256)
grain = Path(sys.argv[1]).read_bytes()


def render(data, width, height, compare=0, strength=1, textured=False, linear=False):
    upload(0, data, width, height, 0x812F, floating=linear)
    upload(1, grain if textured else neutral_grain, 256, 256, 0x2901)
    uniform1f(location(program, b'uCompare'), compare)
    uniform1f(location(program, b'uStrength'), strength)
    uniform1f(location(program, b'uLinearInput'), 1 if linear else 0)
    uniform2f(location(program, b'uImageSize'), width, height)
    gl('glViewport', None, I, I, I, I)(0, 0, width, height)
    gl('glDrawArrays', None, U, I, I)(0x0005, 0, 4)
    output = (C.c_ubyte * (width * height * 4))()
    gl('glReadPixels', None, I, I, I, I, U, U, P)(0, 0, width, height, 0x1908, 0x1401, output)
    assert gl('glGetError', U)() == 0, 'GLES operation failed'
    # Same vertical reversal used by LookSurface.renderPhoto().
    rows = [bytes(output[y*width*4:(y+1)*width*4]) for y in range(height)]
    return b''.join(reversed(rows))


def pixel(data, width, x, y=0):
    start = (y * width + x) * 4
    return tuple(data[start:start+4])


checks = []
markers = bytes([255,0,0,255, 0,255,0,255, 0,0,255,255, 255,255,0,255])
assert render(markers,2,2,compare=1) == markers, 'rotation/channel/readback mismatch'
assert render(markers,2,2,strength=0) == markers, 'zero strength must preserve input'
checks.append('original bypass, RGBA channel order and upright photo readback')

ramp = b''.join(bytes([x,x,x,255]) for x in range(256)) * 16
graded = render(ramp,256,16)
values = [pixel(graded,256,x) for x in range(256)]
assert values[0][:3] == (0,0,0) and values[-1][:3] == (255,255,255), 'black/white endpoints'
lumas = [sum(p[c]*weight for c,weight in enumerate((.2126,.7152,.0722))) for p in values]
assert all(b >= a - 0.01 for a,b in zip(lumas,lumas[1:])), 'tone reversal in ramp'
assert max(b-a for a,b in zip(lumas,lumas[1:])) < 2.1, 'abrupt tone discontinuity'
assert len({round(x) for x in lumas[1:65]}) >= 40, 'excessive crushed shadow range'
assert lumas[192]-lumas[64] > 145, 'midtone separation too weak'
assert values[48][2] > values[48][0], 'B1 shadow cool tint missing'
assert abs(values[220][2]-values[220][0]) <= 1, 'cool tint leaking into highlights'
assert all(p[3] == 255 for p in values)
checks.append('continuous monotone tones, shadow differentiation, black/white endpoints, midtone contrast')
checks.append('cool shadows and neutral highlight grays')

flat = bytes([128,128,128,255]) * (256*128)
clean = render(flat,256,128)
textured = render(flat,256,128,textured=True)
assert textured == render(flat,256,128,textured=True), 'grain changes between identical frames'
differences = [textured[i]-clean[i] for i in range(0,len(flat),4)]
assert max(abs(x) for x in differences) <= 4, 'grain too strong'
assert sum(x != 0 for x in differences) > len(differences)*.1, 'grain invisible'
assert abs(sum(differences)/len(differences)) < .25, 'grain introduces exposure drift'
checks.append('subtle visible luminance grain, no temporal flicker or exposure bias')

levels = [0, .001, .003, .01, .03, .18, .5, .65, 1, 2, 4]
raw_data = [component for value in levels for component in (value, value, value, 1)]
raw_output = render(raw_data, len(levels), 1, linear=True)
raw_values = [sum(pixel(raw_output,len(levels),x)[:3]) for x in range(len(levels))]
assert all(b >= a for a,b in zip(raw_values,raw_values[1:])), 'RAW base rendering reverses tones'
assert raw_values[1] > 0 and raw_values[4] > raw_values[3], 'RAW shadows lose gradation'
assert raw_values[9] > raw_values[8], 'float highlight headroom clipped before rendering'
checks.append('floating-point RAW texture, linear-to-sRGB base, shadow gradation and highlight shoulder')

report = {'renderer': 'local surfaceless EGL / GLES', 'passed': checks,
          'not_tested': ['Android build', 'CameraX device capture', 'moto g66j 5G RAW acquisition',
                         'real-device RAW metadata/color accuracy', 'RAW/JPEG fallback capture integration',
                         'visual match to generated reference', 'preview/save ISP equivalence']}
print(json.dumps(report,ensure_ascii=False,indent=2))
(ROOT/'build/host-checks/report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
egl('eglMakeCurrent', U, P, P, P, P)(display,None,None,None)
egl('eglDestroyContext', U, P, P)(display,context)
egl('eglDestroySurface', U, P, P)(display,surface)
egl('eglTerminate', U, P)(display)
