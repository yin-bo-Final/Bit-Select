"use client";
import Link from "next/link";
import { Button, Result } from "antd";
export default function NotFound() {
  return (
    <Result
      className="page-result"
      status="404"
      title="页面走丢了"
      subTitle="这里暂时没有内容，回到商城继续发现好物吧。"
      extra={
        <Link href="/">
          <Button type="primary">返回商城</Button>
        </Link>
      }
    />
  );
}
