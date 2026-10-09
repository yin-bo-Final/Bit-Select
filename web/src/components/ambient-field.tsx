"use client";

import { memo, useEffect, useRef } from "react";
import { useSession } from "./providers";

const vertexSource = `
attribute vec2 a_position;
void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
`;

// A transparent, low-resolution light field, not a replacement for content.
const fragmentSource = `
precision mediump float;
uniform vec2 u_resolution;
uniform vec2 u_pointer;
uniform float u_time;
uniform float u_dark;
void main() {
  vec2 uv = gl_FragCoord.xy / u_resolution;
  vec2 p = (uv - .5) * vec2(u_resolution.x / u_resolution.y, 1.0);
  float t = u_time * .075;
  vec2 center = vec2(.36, .09) + u_pointer * .045;
  vec2 q = p - center;
  float angle = -.36 + sin(t) * .045;
  q = mat2(cos(angle), -sin(angle), sin(angle), cos(angle)) * q;
  float radius = length(q * vec2(.65, 1.15));
  float ribbon = abs(radius - (.48 + .014 * sin(q.x * 8.0 + t)));
  float light = exp(-ribbon * 58.0) * .2 + exp(-ribbon * 14.0) * .075;
  float wave = p.y + .29 + sin(p.x * 2.3 + t * .8) * .14;
  float line = exp(-abs(wave) * 130.0) * .085;
  float falloff = 1.0 - smoothstep(.0, 1.2, length(q));
  vec3 cobalt = mix(vec3(.15,.36,.94), vec3(.26,.5,1.0), u_dark);
  vec3 ice = mix(vec3(.25,.7,.82), vec3(.3,.73,.95), u_dark);
  vec3 color = mix(cobalt, ice, smoothstep(-.6,.9,q.x));
  float alpha = (light * falloff + line) * mix(.7,1.15,u_dark);
  gl_FragColor = vec4(color, alpha);
}
`;

function typingTarget(target: EventTarget | null) {
  return (
    target instanceof HTMLElement &&
    (target.matches("input, textarea, [role='textbox']") ||
      target.isContentEditable)
  );
}

/** Decorative leaf: no React renders on pointer movement, no scroll listeners. */
export const AmbientField = memo(function AmbientField() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const fieldRef = useRef<HTMLDivElement>(null);
  const { dark, effectsEnabled } = useSession();

  useEffect(() => {
    const canvas = canvasRef.current;
    const field = fieldRef.current;
    if (!canvas || !field) return;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
    const pointer = window.matchMedia("(hover: hover) and (pointer: fine)");
    let gl: WebGLRenderingContext | null = null;
    let program: WebGLProgram | null = null;
    let buffer: WebGLBuffer | null = null;
    const shaders: WebGLShader[] = [];
    let unavailable = false;
    let frame = 0;
    let lastPaint = 0;
    let elapsed = 0;
    let previousTime = 0;
    let targetX = 0;
    let targetY = 0;
    let currentX = 0;
    let currentY = 0;
    let editing = typingTarget(document.activeElement);
    let active = false;
    let disposed = false;
    let resolution: WebGLUniformLocation | null = null;
    let cursor: WebGLUniformLocation | null = null;
    let time: WebGLUniformLocation | null = null;

    const release = () => {
      if (!gl) return;
      if (buffer) gl.deleteBuffer(buffer);
      if (program) gl.deleteProgram(program);
      shaders.forEach((shader) => gl?.deleteShader(shader));
      buffer = null;
      program = null;
    };
    const initialize = () => {
      if (gl || unavailable) return;
      try {
        gl = canvas.getContext("webgl", {
          alpha: true,
          antialias: false,
          depth: false,
          stencil: false,
          premultipliedAlpha: false,
          powerPreference: "low-power",
        });
        if (!gl) throw new Error("No decorative graphics context");
        for (const [type, source] of [
          [gl.VERTEX_SHADER, vertexSource],
          [gl.FRAGMENT_SHADER, fragmentSource],
        ] as const) {
          const shader = gl.createShader(type);
          if (!shader) throw new Error("No shader");
          shaders.push(shader);
          gl.shaderSource(shader, source);
          gl.compileShader(shader);
          if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS))
            throw new Error("Shader unavailable");
        }
        program = gl.createProgram();
        if (!program) throw new Error("No program");
        shaders.forEach((shader) => gl!.attachShader(program!, shader));
        gl.linkProgram(program);
        if (!gl.getProgramParameter(program, gl.LINK_STATUS))
          throw new Error("Program unavailable");
        gl.useProgram(program);
        buffer = gl.createBuffer();
        if (!buffer) throw new Error("No buffer");
        gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
        gl.bufferData(
          gl.ARRAY_BUFFER,
          new Float32Array([-1, -1, 3, -1, -1, 3]),
          gl.STATIC_DRAW,
        );
        const position = gl.getAttribLocation(program, "a_position");
        gl.enableVertexAttribArray(position);
        gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
        resolution = gl.getUniformLocation(program, "u_resolution");
        cursor = gl.getUniformLocation(program, "u_pointer");
        time = gl.getUniformLocation(program, "u_time");
        gl.uniform1f(gl.getUniformLocation(program, "u_dark"), dark ? 1 : 0);
        canvas.dataset.renderer = "webgl";
      } catch {
        // The CSS field remains useful on low-power devices or unavailable GPUs.
        release();
        gl = null;
        unavailable = true;
        canvas.dataset.renderer = "css";
      }
    };
    const paint = () => {
      if (gl && program && !gl.isContextLost()) {
        gl.uniform2f(resolution, canvas.width, canvas.height);
        gl.uniform2f(cursor, currentX, currentY);
        gl.uniform1f(time, elapsed / 1000);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      }
      field.style.setProperty("--ambient-x", `${currentX * 10}px`);
      field.style.setProperty("--ambient-y", `${-currentY * 10}px`);
    };
    const tick = (now: number) => {
      if (!active || disposed) return;
      if (now - lastPaint >= 1000 / 30) {
        elapsed += previousTime ? Math.min(80, now - previousTime) : 0;
        previousTime = now;
        lastPaint = now;
        currentX += (targetX - currentX) * 0.12;
        currentY += (targetY - currentY) * 0.12;
        paint();
      }
      frame = requestAnimationFrame(tick);
    };
    const resize = () => {
      const width = Math.max(1, window.innerWidth);
      const height = Math.max(1, window.innerHeight);
      // Keep under 700k pixels regardless of Retina resolution.
      const scale = Math.min(
        window.devicePixelRatio || 1,
        1.25,
        Math.sqrt(700000 / (width * height)),
      );
      canvas.width = Math.max(1, Math.floor(width * scale));
      canvas.height = Math.max(1, Math.floor(height * scale));
      gl?.viewport(0, 0, canvas.width, canvas.height);
    };
    const synchronize = () => {
      if (disposed) return;
      cancelAnimationFrame(frame);
      active =
        effectsEnabled &&
        !reduced.matches &&
        pointer.matches &&
        window.innerWidth >= 768 &&
        !document.hidden &&
        !editing;
      canvas.dataset.motion = active ? "running" : "static";
      if (active) initialize();
      resize();
      previousTime = 0;
      if (!document.hidden) paint();
      if (active) frame = requestAnimationFrame(tick);
    };
    const move = (event: PointerEvent) => {
      if (!active || event.pointerType !== "mouse") return;
      targetX = (event.clientX / window.innerWidth) * 2 - 1;
      targetY = 1 - (event.clientY / window.innerHeight) * 2;
    };
    const leave = () => {
      targetX = 0;
      targetY = 0;
    };
    const focus = () => {
      // Let focusout settle before checking the next element. No React work per key.
      queueMicrotask(() => {
        if (disposed) return;
        editing = typingTarget(document.activeElement);
        synchronize();
      });
    };
    const lost = () => {
      unavailable = true;
      active = false;
      cancelAnimationFrame(frame);
      canvas.dataset.renderer = "css";
      canvas.dataset.motion = "static";
    };
    canvas.dataset.renderer = "css";
    synchronize();
    window.addEventListener("pointermove", move, { passive: true });
    document.addEventListener("pointerleave", leave);
    window.addEventListener("resize", synchronize, { passive: true });
    document.addEventListener("visibilitychange", synchronize);
    document.addEventListener("focusin", focus);
    document.addEventListener("focusout", focus);
    canvas.addEventListener("webglcontextlost", lost);
    reduced.addEventListener("change", synchronize);
    pointer.addEventListener("change", synchronize);
    return () => {
      disposed = true;
      active = false;
      cancelAnimationFrame(frame);
      window.removeEventListener("pointermove", move);
      document.removeEventListener("pointerleave", leave);
      window.removeEventListener("resize", synchronize);
      document.removeEventListener("visibilitychange", synchronize);
      document.removeEventListener("focusin", focus);
      document.removeEventListener("focusout", focus);
      canvas.removeEventListener("webglcontextlost", lost);
      reduced.removeEventListener("change", synchronize);
      pointer.removeEventListener("change", synchronize);
      release();
    };
  }, [dark, effectsEnabled]);

  return (
    <div ref={fieldRef} className="ambient-field" aria-hidden="true">
      <div className="ambient-refraction" />
      <canvas ref={canvasRef} />
    </div>
  );
});
