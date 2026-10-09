"use client";

import { memo, useEffect, useRef } from "react";
import { ProductImage } from "@/components/common";
import type { Product } from "@/lib/types";
import { useSession } from "./providers";

type ProductStageProps = {
  product: Pick<Product, "imageUrl" | "name">;
  variant?: "hero" | "detail";
  priority?: boolean;
};

/** Pointer motion is decorative; the image and its accessible name never depend on it. */
export const ProductStage = memo(function ProductStage({
  product,
  variant = "hero",
  priority = false,
}: ProductStageProps) {
  const stageRef = useRef<HTMLDivElement>(null);
  const { effectsEnabled } = useSession();

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage || !effectsEnabled) return;
    const motion = window.matchMedia(
      "(prefers-reduced-motion: no-preference) and (hover: hover) and (pointer: fine)",
    );
    let frame = 0;
    let bounds: DOMRect | null = null;

    const reset = () => {
      cancelAnimationFrame(frame);
      bounds = null;
      stage.style.removeProperty("--stage-x");
      stage.style.removeProperty("--stage-y");
      stage.style.removeProperty("--stage-rotate-x");
      stage.style.removeProperty("--stage-rotate-y");
      stage.removeAttribute("data-interacting");
    };
    const enter = () => {
      if (motion.matches) bounds = stage.getBoundingClientRect();
    };
    const move = (event: PointerEvent) => {
      if (!motion.matches || event.pointerType !== "mouse") return;
      bounds ||= stage.getBoundingClientRect();
      const x = Math.max(
        -1,
        Math.min(1, ((event.clientX - bounds.left) / bounds.width - 0.5) * 2),
      );
      const y = Math.max(
        -1,
        Math.min(1, ((event.clientY - bounds.top) / bounds.height - 0.5) * 2),
      );
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        stage.style.setProperty("--stage-x", `${x * 11}px`);
        stage.style.setProperty("--stage-y", `${y * 8}px`);
        stage.style.setProperty("--stage-rotate-x", `${-y * 4}deg`);
        stage.style.setProperty("--stage-rotate-y", `${x * 4}deg`);
        stage.setAttribute("data-interacting", "true");
      });
    };
    stage.addEventListener("pointerenter", enter);
    stage.addEventListener("pointermove", move);
    stage.addEventListener("pointerleave", reset);
    stage.addEventListener("pointercancel", reset);
    motion.addEventListener("change", reset);
    window.addEventListener("resize", reset);
    return () => {
      reset();
      stage.removeEventListener("pointerenter", enter);
      stage.removeEventListener("pointermove", move);
      stage.removeEventListener("pointerleave", reset);
      stage.removeEventListener("pointercancel", reset);
      motion.removeEventListener("change", reset);
      window.removeEventListener("resize", reset);
    };
  }, [effectsEnabled]);

  return (
    <div
      ref={stageRef}
      className={`bs-product-stage bs-product-stage--${variant}`}
    >
      <div className="bs-stage-glow" aria-hidden="true" />
      <div
        className="bs-stage-orbit bs-stage-orbit--outer"
        aria-hidden="true"
      />
      <div
        className="bs-stage-orbit bs-stage-orbit--inner"
        aria-hidden="true"
      />
      <div className="bs-stage-depth" aria-hidden="true" />
      <div className="bs-stage-surface">
        <div className="bs-stage-image">
          <ProductImage
            key={product.imageUrl}
            product={product}
            priority={priority}
            sizes="(max-width: 767px) 90vw, (max-width: 1280px) 48vw, 640px"
          />
        </div>
      </div>
    </div>
  );
});
