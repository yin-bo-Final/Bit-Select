"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Alert, Button, Form, Input, Tabs } from "antd";
import {
  ArrowRight,
  Fingerprint,
  LockKey,
  ShoppingBag,
  Sparkle,
  UserCircle,
} from "@phosphor-icons/react";
import { ProductImage } from "@/components/common";
import { post, errorText } from "@/lib/api";
import { useSession } from "@/components/providers";

export default function LoginPage() {
  const [mode, setMode] = useState("login");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const { refresh } = useSession();
  const router = useRouter();
  const submit = async (values: {
    username: string;
    password: string;
    nickname?: string;
  }) => {
    setBusy(true);
    setError("");
    try {
      await post(`/auth/${mode}`, values);
      await refresh();
      router.push("/");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="auth-layout commerce-auth">
      <div className="auth-intro">
        <div className="commerce-auth-brand">
          <span className="brand-mark large">b.</span>
          <span>
            比特严选<span>为日常，选得更好</span>
          </span>
        </div>
        <h1>
          你的日常，
          <br />
          值得认真挑选。
        </h1>
        <p>收藏喜欢的生活，从一次好选择开始。</p>
        <Link href="/">
          先逛逛商品 <ArrowRight size={18} />
        </Link>
        <div className="commerce-auth-display" aria-hidden="true">
          <div className="commerce-auth-product commerce-auth-product-main">
            <ProductImage
              product={{ name: "无线键盘", imageUrl: "/products/BS-0041.svg" }}
            />
          </div>
          <div className="commerce-auth-product commerce-auth-product-small">
            <ProductImage
              product={{
                name: "氮化镓充电器",
                imageUrl: "/products/BS-0001.svg",
              }}
            />
          </div>
        </div>
        <div className="commerce-auth-benefits">
          <span>
            <ShoppingBag size={20} />
            精选商品
          </span>
          <span>
            <Sparkle size={20} />
            专属导购
          </span>
        </div>
      </div>
      <section className="auth-panel">
        <div className="commerce-auth-icon">
          <Fingerprint size={28} weight="duotone" />
        </div>
        <h2>{mode === "login" ? "欢迎回来" : "认识一下"}</h2>
        <p className="muted">
          {mode === "login"
            ? "登录后继续你的选购旅程。"
            : "创建账户，即可体验专属导购与平台购物。"}
        </p>
        <Tabs
          activeKey={mode}
          onChange={(key) => {
            setMode(key);
            setError("");
          }}
          items={[
            { key: "login", label: "登录" },
            { key: "register", label: "注册" },
          ]}
        />
        {error && (
          <Alert title={error} type="error" showIcon className="form-alert" />
        )}
        <Form
          layout="vertical"
          key={mode}
          onFinish={submit}
          requiredMark={false}
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: "请输入用户名" },
              {
                pattern: /^[a-zA-Z0-9_]{3,32}$/,
                message: "请输入 3-32 位字母、数字或下划线",
              },
            ]}
          >
            <Input
              prefix={<UserCircle size={19} aria-hidden="true" />}
              autoComplete="username"
              placeholder="输入用户名"
              maxLength={32}
            />
          </Form.Item>
          {mode === "register" && (
            <Form.Item
              name="nickname"
              label="怎么称呼你"
              rules={[
                { required: true, message: "请输入昵称" },
                { max: 32, message: "昵称不超过 32 个字符" },
              ]}
            >
              <Input
                autoComplete="nickname"
                placeholder="输入昵称"
                maxLength={32}
              />
            </Form.Item>
          )}
          <Form.Item
            name="password"
            label="密码"
            rules={[
              { required: true, message: "请输入密码" },
              { min: 8, max: 72, message: "密码长度为 8-72 个字符" },
            ]}
          >
            <Input.Password
              prefix={<LockKey size={19} aria-hidden="true" />}
              autoComplete={
                mode === "register" ? "new-password" : "current-password"
              }
              placeholder="至少 8 个字符"
              maxLength={72}
            />
          </Form.Item>
          <Button
            block
            type="primary"
            size="large"
            htmlType="submit"
            loading={busy}
          >
            {mode === "login" ? "登录" : "创建账户"}
          </Button>
        </Form>
        <p className="form-note">
          登录状态将在连续 7 天未使用后失效。平台余额由管理员分配。
        </p>
      </section>
    </div>
  );
}
