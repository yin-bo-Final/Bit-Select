"use client";
import { useCallback, useEffect, useState } from "react";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Empty,
  Form,
  Input,
  Modal,
  Table,
  Tag,
} from "antd";
import { api, post, date, money, errorText } from "@/lib/api";
import type { RefundRequest } from "@/lib/types";
const labels: Record<string, string> = {
  REQUESTED: "待审核",
  REJECTED: "已拒绝",
  COMPLETED: "已退款",
};
export function RefundRequestButton({
  orderId,
  onSuccess,
}: {
  orderId: string;
  onSuccess: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const { message } = App.useApp();
  const submit = async (values: { reason: string }) => {
    setBusy(true);
    try {
      await post(`/orders/${orderId}/refund-request`, values);
      message.success("退货申请已提交，请等待管理员审核");
      setOpen(false);
      onSuccess();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <Button onClick={() => setOpen(true)}>申请退货</Button>
      <Modal
        title="申请整单退货"
        open={open}
        onCancel={() => {
          if (!busy) setOpen(false);
        }}
        footer={null}
        destroyOnHidden
      >
        <p className="muted">
          已发货订单需管理员确认商品退回并验收后，余额才会退回钱包。
        </p>
        <Form layout="vertical" onFinish={submit}>
          <Form.Item
            name="reason"
            label="退货原因"
            rules={[
              { required: true, whitespace: true, message: "请说明退货原因" },
              { min: 3, max: 500, message: "请填写 3-500 个字符" },
            ]}
          >
            <Input.TextArea rows={4} maxLength={500} />
          </Form.Item>
          <Button type="primary" block htmlType="submit" loading={busy}>
            提交申请
          </Button>
        </Form>
      </Modal>
    </>
  );
}
export function Refunds({
  admin = false,
  revision = 0,
}: {
  admin?: boolean;
  revision?: number;
}) {
  const [items, setItems] = useState<RefundRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [review, setReview] = useState<{
    request: RefundRequest;
    action: "approve" | "reject";
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const { message } = App.useApp();
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(
        (
          await api<{ items: RefundRequest[] }>(
            admin ? "/admin/refund-requests" : "/refund-requests",
          )
        ).items,
      );
      setError("");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [admin]);
  useEffect(() => {
    void load();
  }, [load, revision]);
  const submit = async (values: {
    reason: string;
    goodsReceived?: boolean;
  }) => {
    if (!review) return;
    setBusy(true);
    try {
      await post(
        `/admin/refund-requests/${review.request.id}/${review.action}`,
        values,
      );
      message.success(
        review.action === "approve"
          ? "退款完成，商品已重新入库"
          : "已拒绝退货申请",
      );
      setReview(null);
      await load();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="refund-section">
      <div className="heading-with-action">
        <h2>{admin ? "售后审核" : "我的售后"}</h2>
        <Button onClick={load}>刷新售后</Button>
      </div>
      {error && <Alert type="error" title={error} />}
      <Table<RefundRequest>
        rowKey="id"
        dataSource={items}
        loading={loading}
        scroll={{ x: 850 }}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
        locale={{ emptyText: <Empty description="暂无售后申请" /> }}
        columns={[
          { title: "订单号", dataIndex: "orderNo" },
          { title: "金额", dataIndex: "totalCents", render: money },
          { title: "原因", dataIndex: "reason", width: 220 },
          {
            title: "状态",
            dataIndex: "status",
            render: (status) => <Tag>{labels[status] || status}</Tag>,
          },
          { title: "申请时间", dataIndex: "createdAt", render: date },
          {
            title: admin ? "审核" : "审核说明",
            key: "review",
            render: (_, item) =>
              admin && item.status === "REQUESTED" ? (
                <div className="order-actions">
                  <Button
                    size="small"
                    type="primary"
                    onClick={() =>
                      setReview({ request: item, action: "approve" })
                    }
                  >
                    验收退款
                  </Button>
                  <Button
                    size="small"
                    onClick={() =>
                      setReview({ request: item, action: "reject" })
                    }
                  >
                    拒绝
                  </Button>
                </div>
              ) : (
                item.reviewReason || "等待处理"
              ),
          },
        ]}
      />
      <Modal
        title={
          review?.action === "approve" ? "确认退货验收并退款" : "拒绝退货申请"
        }
        open={!!review}
        onCancel={() => {
          if (!busy) setReview(null);
        }}
        footer={null}
        destroyOnHidden
      >
        <p>
          订单 {review?.request.orderNo}，退款金额{" "}
          {money(review?.request.totalCents)}。
        </p>
        <Form key={review?.action} layout="vertical" onFinish={submit}>
          {review?.action === "approve" && (
            <Form.Item
              name="goodsReceived"
              valuePropName="checked"
              rules={[
                {
                  validator: async (_, value) => {
                    if (value !== true)
                      throw new Error("请先确认商品已退回并验收");
                  },
                },
              ]}
            >
              <Checkbox>我已确认整单商品退回并验收，允许恢复库存。</Checkbox>
            </Form.Item>
          )}
          <Form.Item
            name="reason"
            label="审核说明"
            rules={[
              { required: true, whitespace: true, message: "请填写审核说明" },
              { min: 3, max: 500 },
            ]}
          >
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
          <Button type="primary" block htmlType="submit" loading={busy}>
            {review?.action === "approve" ? "确认退款并入库" : "确认拒绝"}
          </Button>
        </Form>
      </Modal>
    </section>
  );
}
