"use client";
import { useCallback, useEffect, useState } from "react";
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Switch,
  Tag,
} from "antd";
import {
  Plus,
  MapPin,
  PencilSimple,
  Trash,
  House,
} from "@phosphor-icons/react";
import { api, post, errorText } from "@/lib/api";
import type { SavedAddress } from "@/lib/types";

export function AddressBook() {
  const [addresses, setAddresses] = useState<SavedAddress[]>([]);
  const [editing, setEditing] = useState<SavedAddress | "new" | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [form] = Form.useForm<SavedAddress>();
  const { message } = App.useApp();
  const load = useCallback(async () => {
    try {
      setAddresses((await api<{ items: SavedAddress[] }>("/addresses")).items);
      setError("");
    } catch (e) {
      setError(errorText(e));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const open = (address: SavedAddress | "new") => {
    form.resetFields();
    if (address !== "new") form.setFieldsValue(address);
    setEditing(address);
  };
  const save = async (values: SavedAddress) => {
    setLoading(true);
    try {
      if (editing === "new")
        await post("/addresses", {
          ...values,
          isDefault: values.isDefault || false,
        });
      else if (editing)
        await api(`/addresses/${editing.id}`, {
          method: "PUT",
          body: JSON.stringify(values),
        });
      message.success("地址已保存");
      setEditing(null);
      await load();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setLoading(false);
    }
  };
  const remove = async (id: number) => {
    try {
      await api(`/addresses/${id}`, { method: "DELETE" });
      await load();
    } catch (e) {
      message.error(errorText(e));
    }
  };
  return (
    <section className="address-section commerce-section commerce-addresses">
      <div className="heading-with-action">
        <div className="commerce-section-title">
          <MapPin size={23} />
          <div>
            <h2>收货地址</h2>
            <p>保存常用地址，下次下单更从容。</p>
          </div>
        </div>
        <Button
          icon={<Plus size={17} />}
          onClick={() => open("new")}
          disabled={addresses.length >= 20}
        >
          添加地址
        </Button>
      </div>
      {error && (
        <Alert
          type="error"
          title={error}
          action={<Button onClick={load}>重试</Button>}
        />
      )}
      <div className="address-grid">
        {!addresses.length ? (
          <Empty
            image={<House size={48} weight="thin" />}
            description="添加常用地址，让下次购买更方便"
          />
        ) : (
          addresses.map((address) => (
            <article
              className={`address-card ${address.isDefault ? "commerce-address-default" : ""}`}
              key={address.id}
            >
              <div>
                <span className="commerce-address-marker">
                  <House size={19} />
                </span>
                <strong>{address.recipient}</strong>
                <span>{address.phone}</span>
                {address.isDefault && <Tag>默认地址</Tag>}
              </div>
              <p>{address.detail}</p>
              <footer>
                <Button
                  type="text"
                  size="small"
                  icon={<PencilSimple size={16} />}
                  onClick={() => open(address)}
                >
                  编辑
                </Button>
                <Popconfirm
                  title="删除此收货地址？"
                  okText="删除"
                  cancelText="保留"
                  onConfirm={() => remove(address.id)}
                >
                  <Button type="text" size="small" icon={<Trash size={16} />}>
                    删除
                  </Button>
                </Popconfirm>
              </footer>
            </article>
          ))
        )}
      </div>
      <Modal
        className="commerce-modal"
        title={editing === "new" ? "添加地址" : "编辑地址"}
        open={!!editing}
        onCancel={() => {
          if (!loading) setEditing(null);
        }}
        footer={null}
        forceRender
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={save}
          requiredMark={false}
        >
          <Form.Item
            name="recipient"
            label="收货人"
            rules={[
              { required: true, whitespace: true, message: "请输入收货人" },
              { max: 40 },
            ]}
          >
            <Input autoComplete="name" maxLength={40} />
          </Form.Item>
          <Form.Item
            name="phone"
            label="联系电话"
            rules={[
              { required: true, message: "请输入联系电话" },
              { pattern: /^[+\d\s-]{6,20}$/, message: "请输入有效的联系电话" },
            ]}
          >
            <Input autoComplete="tel" maxLength={20} />
          </Form.Item>
          <Form.Item
            name="detail"
            label="收货地址"
            rules={[
              { required: true, whitespace: true, message: "请输入完整地址" },
              { min: 5, max: 300, message: "地址为 5-300 个字符" },
            ]}
          >
            <Input.TextArea
              autoComplete="street-address"
              rows={3}
              maxLength={300}
            />
          </Form.Item>
          <Form.Item
            name="isDefault"
            label="设为默认地址"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Button block type="primary" htmlType="submit" loading={loading}>
            保存地址
          </Button>
        </Form>
      </Modal>
    </section>
  );
}
