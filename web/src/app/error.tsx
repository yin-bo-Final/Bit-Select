"use client";
import { Button, Result } from "antd";
export default function ErrorPage({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <Result
      className="page-result"
      status="error"
      title="页面暂时未能加载"
      subTitle="请重新尝试。如果问题持续，请检查本地服务是否正常运行。"
      extra={
        <Button type="primary" onClick={reset}>
          重新加载
        </Button>
      }
    />
  );
}
